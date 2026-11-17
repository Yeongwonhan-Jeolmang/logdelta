// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.util.Text;
import io.github.logdelta.gui.model.DiffViewModel;
import io.github.logdelta.gui.model.SearchModel;
import io.github.logdelta.gui.model.ViewFilter;
import io.github.logdelta.gui.model.ViewRow;
import io.github.logdelta.gui.theme.DiffPalette;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;

/// The comparison view: file header, [FilterBar], [DiffCanvas] in a scroll pane,
/// [OverviewRuler], shared horizontal scroll bar and [SearchBar].
///
/// Also implements navigation (next/previous change, problems, search matches) and keyboard
/// handling for the canvas.
public final class DiffView extends JPanel {

    /// How far one keyboard step moves the divider between the panes.
    public static final double SPLIT_STEP = 0.05;

    private final DiffCanvas canvas = new DiffCanvas();
    private final JScrollPane scroll = new JScrollPane(canvas);
    private final OverviewRuler ruler = new OverviewRuler(scroll);
    private final JScrollBar horizontal = new JScrollBar(JScrollBar.HORIZONTAL);
    private final SearchBar searchBar = new SearchBar();
    private final Header header = new Header();
    private final FilterBar filterBar = new FilterBar(this::setFilter);

    private DiffViewModel model;
    private SearchModel search = SearchModel.empty();
    private int searchIndex = -1;
    private ViewFilter filter = ViewFilter.NONE;
    private java.util.function.Consumer<ViewFilter> filterListener = _ -> { };
    private boolean collapse = true;
    private int context = 3;

    /// Creates an empty view.
    public DiffView() {
        super(new BorderLayout());
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        scroll.getVerticalScrollBar().setUnitIncrement(canvas.rowHeight());
        scroll.setWheelScrollingEnabled(true);

        JPanel north = new JPanel(new BorderLayout());
        north.add(searchBar, BorderLayout.NORTH);
        north.add(filterBar, BorderLayout.CENTER);
        north.add(header, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout());
        center.add(scroll, BorderLayout.CENTER);
        center.add(ruler, BorderLayout.EAST);

        add(north, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(horizontal, BorderLayout.SOUTH);

        horizontal.addAdjustmentListener(e -> canvas.setHorizontalOffset(e.getValue()));
        canvas.addMouseWheelListener(e -> {
            if (e.isShiftDown()) {
                horizontal.setValue(horizontal.getValue() + e.getUnitsToScroll() * horizontal.getUnitIncrement());
                e.consume();
            } else {
                scroll.dispatchEvent(javax.swing.SwingUtilities.convertMouseEvent(canvas, e, scroll));
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateHorizontalRange();
            }
        });
        canvas.addListener(new DiffCanvas.Listener() {
            @Override
            public void foldExpanded() {
                ruler.repaint();
            }

            @Override
            public void splitChanged(double split) {
                header.repaint();
                updateHorizontalRange();
                ruler.repaint();
            }

            @Override
            public void gapClicked() {
                filterBar.setFilter(ViewFilter.NONE, true);
            }
        });

        installKeys();
        searchBar.setListener(new SearchBar.Listener() {
            @Override
            public void queryChanged(String query, boolean regex, boolean matchCase) {
                runSearch(query, regex, matchCase);
            }

            @Override
            public void step(boolean forward) {
                stepSearch(forward);
            }

            @Override
            public void closed() {
                search = SearchModel.empty();
                searchIndex = -1;
                canvas.setSearch(search, null);
                canvas.requestFocusInWindow();
            }
        });
    }

    public DiffCanvas canvas() {
        return canvas;
    }

    /// The current model, or `null`.
    public DiffViewModel model() {
        return model;
    }

    /// Displays a result.
    ///
    /// @param keepPosition keep the scroll position (used when re-comparing the same files)
    public void show(DiffResult result, boolean collapse, int context, boolean keepPosition) {
        Point position = scroll.getViewport().getViewPosition();
        int hValue = horizontal.getValue();
        this.collapse = collapse;
        this.context = context;
        model = result == null ? null : new DiffViewModel(result, collapse, context, filter);
        updateFilterCounts();
        canvas.setModel(model);
        ruler.setModel(model);
        header.setFiles(result == null ? null : result.leftFile(), result == null ? null : result.rightFile());
        updateHorizontalRange();
        if (searchBar.isVisible()) {
            runSearch(searchBar.query(), searchBar.isRegex(), searchBar.isMatchCase());
        }
        if (keepPosition) {
            javax.swing.SwingUtilities.invokeLater(() -> {
                scroll.getViewport().setViewPosition(position);
                horizontal.setValue(hValue);
            });
        } else {
            scroll.getViewport().setViewPosition(new Point(0, 0));
            horizontal.setValue(0);
        }
    }

    /// Re-applies folding (e.g. after changing the context size), keeping the selected row.
    public void refold(boolean collapse, int context) {
        this.collapse = collapse;
        this.context = context;
        rebuildModel();
    }

    /// Shows only the rows that pass `f`, keeping the selected row if it is still visible.
    public void setFilter(ViewFilter f) {
        if (f.equals(filter)) {
            return;
        }
        filter = f;
        if (!filterBar.filter().equals(f)) {
            filterBar.setFilter(f, false);
        }
        rebuildModel();
        filterListener.accept(f);
    }

    /// Receives every filter change, e.g. to keep menu items in sync.
    public void setFilterListener(java.util.function.Consumer<ViewFilter> listener) {
        filterListener = listener;
    }

    /// The active filter.
    public ViewFilter filter() {
        return filter;
    }

    /// Moves the keyboard focus to the text filter.
    public void focusFilter() {
        filterBar.focusText();
    }

    private void rebuildModel() {
        if (model == null) {
            return;
        }
        int selectedRow = selectedDiffRow();
        model = new DiffViewModel(model.result(), collapse, context, filter);
        canvas.setModel(model);
        ruler.setModel(model);
        updateFilterCounts();
        if (searchBar.isVisible()) {
            runSearch(searchBar.query(), searchBar.isRegex(), searchBar.isMatchCase());
        }
        if (selectedRow >= 0) {
            int v = model.reveal(selectedRow);
            if (v >= 0) {
                javax.swing.SwingUtilities.invokeLater(() -> canvas.selectCentered(v));
            }
        }
    }

    private void updateFilterCounts() {
        if (model == null) {
            filterBar.setCounts(0, 0, false);
        } else {
            filterBar.setCounts(model.visibleRowCount(), model.result().rows().size(), filter.active());
        }
    }

    /// Turns line wrapping on or off.
    public void setWrap(boolean value) {
        canvas.setWrap(value);
        updateHorizontalRange();
        ruler.repaint();
    }

    /// Turns syntax colouring on or off.
    public void setSyntaxColoring(boolean value) {
        canvas.setSyntaxColoring(value);
    }

    /// Shows or hides the elapsed-time column.
    public void setShowElapsedTime(boolean value) {
        canvas.setShowElapsedTime(value);
        updateHorizontalRange();
    }

    /// Whether the view is scrolled to (or within a row of) the end.
    public boolean isAtEnd() {
        java.awt.Rectangle r = scroll.getViewport().getViewRect();
        return r.y + r.height >= canvas.getHeight() - canvas.rowHeight();
    }

    /// Scrolls to the last row, as `tail -f` does.
    public void scrollToEnd() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            canvas.revalidate();
            javax.swing.SwingUtilities.invokeLater(() -> {
                int h = canvas.getPreferredSize().height;
                canvas.scrollRectToVisible(new java.awt.Rectangle(0, Math.max(0, h - 1), 1, 1));
            });
        });
    }

    public void setPalette(DiffPalette palette) {
        canvas.setPalette(palette);
        ruler.setPalette(palette);
        header.repaint();
        scroll.getViewport().setBackground(palette.background());
    }

    public void setEditorFont(Font font) {
        canvas.setEditorFont(font);
        scroll.getVerticalScrollBar().setUnitIncrement(canvas.rowHeight());
        header.repaint();
        updateHorizontalRange();
    }

    public void setShowNormalized(boolean value) {
        canvas.setShowNormalized(value);
        updateHorizontalRange();
        if (searchBar.isVisible()) {
            runSearch(searchBar.query(), searchBar.isRegex(), searchBar.isMatchCase());
        }
    }

    private void updateHorizontalRange() {
        if (canvas.isWrap()) {
            horizontal.setValue(0);
            horizontal.setVisible(false);
            return;
        }
        int extent = Math.max(1, canvas.textAreaWidth());
        int max = Math.max(extent, canvas.maxTextWidth());
        horizontal.setValues(Math.min(horizontal.getValue(), Math.max(0, max - extent)), extent, 0, max);
        horizontal.setUnitIncrement(16);
        horizontal.setBlockIncrement(extent / 2);
        horizontal.setVisible(max > extent);
    }

    // --- navigation ----------------------------------------------------------------------------

    /// Diff row index of the selection, or `-1`.
    public int selectedDiffRow() {
        int v = canvas.selected();
        if (model == null || v < 0 || v >= model.size()) {
            return -1;
        }
        return model.get(v).firstRow();
    }

    /// Selects the next block of changes.
    public boolean nextChange() {
        if (model == null) {
            return false;
        }
        int v = model.nextChange(currentViewIndex());
        if (v >= 0) {
            canvas.selectCentered(v);
        }
        return v >= 0;
    }

    /// Selects the previous block of changes.
    public boolean previousChange() {
        if (model == null) {
            return false;
        }
        int cur = canvas.selected() < 0 ? model.size() : canvas.selected();
        int v = model.previousChange(cur);
        if (v >= 0) {
            canvas.selectCentered(v);
        }
        return v >= 0;
    }

    /// Selects the next (`forward`) or previous new problem.
    public boolean stepProblem(boolean forward) {
        if (model == null) {
            return false;
        }
        List<InsightItem> problems = model.result().insights().newProblems();
        int current = selectedDiffRow();
        InsightItem target = null;
        if (forward) {
            for (InsightItem p : problems) {
                if (p.row() > current) {
                    target = p;
                    break;
                }
            }
        } else {
            for (int i = problems.size() - 1; i >= 0; i--) {
                if (problems.get(i).row() < current || current < 0) {
                    target = problems.get(i);
                    break;
                }
            }
        }
        if (target != null) {
            goToRow(target.row());
        }
        return target != null;
    }

    /// Reveals and selects a diff row.
    public void goToRow(int row) {
        if (model == null || row < 0) {
            return;
        }
        int found = model.reveal(row);
        if (found < 0 && filter.active()) {
            // The target is hidden by the filter: show everything rather than going nowhere.
            filterBar.setFilter(ViewFilter.NONE, true);
            found = model.reveal(row);
        }
        int v = found;
        canvas.revalidate();
        ruler.repaint();
        javax.swing.SwingUtilities.invokeLater(() -> canvas.selectCentered(v));
    }

    /// Expands every collapsed region.
    public void expandAll() {
        if (model != null) {
            int row = selectedDiffRow();
            model.expandAll();
            canvas.revalidate();
            ruler.repaint();
            if (row >= 0) {
                javax.swing.SwingUtilities.invokeLater(() -> canvas.selectCentered(model.reveal(row)));
            }
        }
    }

    private int currentViewIndex() {
        if (canvas.selected() >= 0) {
            return canvas.selected();
        }
        return canvas.rowAt(scroll.getViewport().getViewPosition().y) - 1;
    }

    // --- search --------------------------------------------------------------------------------

    /// Opens the find bar, pre-filled with `initial` if given.
    public void openSearch(String initial) {
        searchBar.open(initial);
    }

    private void runSearch(String query, boolean regex, boolean matchCase) {
        search = SearchModel.search(model == null ? null : model.result(), query, regex, matchCase,
                canvas.isShowNormalized());
        searchIndex = -1;
        if (search.error() != null) {
            searchBar.setStatus(search.error(), true);
        } else if (query.isEmpty()) {
            searchBar.setStatus(" ", false);
        } else if (search.size() == 0) {
            searchBar.setStatus("No matches", true);
        } else {
            // Start from the first match at or after the current position.
            int from = selectedDiffRow();
            searchIndex = 0;
            for (int i = 0; i < search.size(); i++) {
                if (search.matches().get(i).row() >= from) {
                    searchIndex = i;
                    break;
                }
            }
            showMatch();
            return;
        }
        canvas.setSearch(search, null);
    }

    private void stepSearch(boolean forward) {
        if (search.size() == 0) {
            return;
        }
        searchIndex = Math.floorMod(searchIndex + (forward ? 1 : -1), search.size());
        showMatch();
    }

    private void showMatch() {
        SearchModel.Match m = search.matches().get(searchIndex);
        canvas.setSearch(search, m);
        searchBar.setStatus(String.format(Locale.ROOT, "%,d of %,d", searchIndex + 1, search.size()), false);
        goToRow(m.row());
        // Scroll horizontally so the match is visible.
        javax.swing.SwingUtilities.invokeLater(() -> {
            int col = m.start() * canvas.getFontMetrics(canvas.editorFont()).charWidth('m');
            int extent = horizontal.getVisibleAmount();
            if (col < horizontal.getValue() || col > horizontal.getValue() + extent - 40) {
                horizontal.setValue(Math.max(0, col - extent / 3));
            }
        });
    }

    // --- keyboard ------------------------------------------------------------------------------

    private void installKeys() {
        bind("row-up", KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), () -> moveSelection(-1));
        bind("row-down", KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), () -> moveSelection(1));
        bind("page-up", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), () -> moveSelection(-pageRows()));
        bind("page-down", KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), () -> moveSelection(pageRows()));
        bind("home", KeyStroke.getKeyStroke(KeyEvent.VK_HOME, KeyEvent.CTRL_DOWN_MASK), () -> canvas.select(0));
        bind("end", KeyStroke.getKeyStroke(KeyEvent.VK_END, KeyEvent.CTRL_DOWN_MASK),
                () -> canvas.select(model == null ? 0 : model.size() - 1));
        bind("left", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),
                () -> horizontal.setValue(horizontal.getValue() - 4 * horizontal.getUnitIncrement()));
        bind("right", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0),
                () -> horizontal.setValue(horizontal.getValue() + 4 * horizontal.getUnitIncrement()));
        bind("split-left", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                () -> canvas.setSplit(canvas.split() - SPLIT_STEP));
        bind("split-right", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK),
                () -> canvas.setSplit(canvas.split() + SPLIT_STEP));
        bind("expand", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), () -> {
            int v = canvas.selected();
            if (model != null && v >= 0 && model.get(v) instanceof ViewRow.Gap) {
                filterBar.setFilter(ViewFilter.NONE, true);
            } else if (model != null && v >= 0 && model.get(v) instanceof ViewRow.Fold fold) {
                model.expand(fold.start());
                canvas.revalidate();
                canvas.repaint();
                ruler.repaint();
            }
        });
    }

    private int pageRows() {
        return Math.max(1, scroll.getViewport().getHeight() / canvas.rowHeight() - 1);
    }

    private void moveSelection(int delta) {
        if (model == null || model.size() == 0) {
            return;
        }
        int v = canvas.selected() < 0 ? canvas.rowAt(scroll.getViewport().getViewPosition().y) : canvas.selected();
        canvas.select(Math.max(0, v) + delta);
    }

    private void bind(String name, KeyStroke key, Runnable action) {
        canvas.getInputMap(JComponent.WHEN_FOCUSED).put(key, name);
        canvas.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    /// Text of the selected row for copying: `side` is "left", "right" or "both".
    public String selectedText(String side) {
        int row = selectedDiffRow();
        if (model == null || row < 0 || !(model.get(canvas.selected()) instanceof ViewRow.Line)) {
            return null;
        }
        DiffResult r = model.result();
        DiffRow d = r.rows().get(row);
        String l = d.hasLeft() ? r.left(d).text() : "";
        String rt = d.hasRight() ? r.right(d).text() : "";
        return switch (side) {
            case "left" -> d.hasLeft() ? l : null;
            case "right" -> d.hasRight() ? rt : null;
            default -> l + "\n" + rt;
        };
    }

    /// Column header showing the two file names above the panes.
    private final class Header extends JComponent {
        private LogFile left;
        private LogFile right;

        Header() {
            setPreferredSize(new Dimension(100, 30));
        }

        void setFiles(LogFile l, LogFile r) {
            this.left = l;
            this.right = r;
            setToolTipText(l == null ? null : "<html>" + Text.escapeHtml(path(l)) + "<br>"
                    + Text.escapeHtml(path(r)) + "</html>");
            repaint();
        }

        private String path(LogFile f) {
            return f.path() == null ? f.displayName() : f.path().toString();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                DiffPalette p = canvas.palette();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(p.gutter());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(p.divider());
                g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
                if (left == null) {
                    return;
                }
                paintFile(g, p, left, "BASELINE", 0, canvas.leftPaneWidth());
                paintFile(g, p, right, "CANDIDATE", canvas.rightPaneX(), canvas.rightPaneWidth());
            } finally {
                g.dispose();
            }
        }

        private void paintFile(Graphics2D g, DiffPalette p, LogFile f, String role, int x, int w) {
            Font base = getFont() == null ? canvas.editorFont() : getFont();
            Font small = base.deriveFont(Font.BOLD, Math.max(9f, base.getSize2D() - 3f));
            g.setFont(small);
            FontMetrics sm = g.getFontMetrics();
            int baseline = (getHeight() - sm.getHeight()) / 2 + sm.getAscent();
            g.setColor(p.lineNumber());
            g.drawString(role, x + 10, baseline);
            int nameX = x + 10 + sm.stringWidth(role) + 10;
            g.setFont(base.deriveFont(Font.BOLD));
            FontMetrics fm = g.getFontMetrics();
            String meta = String.format(Locale.ROOT, "%,d lines · %s · %s%s", f.lineCount(),
                    Text.humanBytes(f.sizeBytes()), f.charset().name(), f.compressed() ? " · gzip" : "");
            g.setColor(p.text());
            int nameBaseline = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            String name = f.displayName();
            int available = w - (nameX - x) - 12;
            Font metaFont = base.deriveFont(Math.max(9f, base.getSize2D() - 1f));
            int metaWidth = getFontMetrics(metaFont).stringWidth(meta);
            boolean showMeta = fm.stringWidth(name) + metaWidth + 16 < available;
            g.drawString(clip(name, fm, showMeta ? available - metaWidth - 16 : available), nameX, nameBaseline);
            if (showMeta) {
                g.setFont(metaFont);
                g.setColor(p.lineNumber());
                g.drawString(meta, nameX + fm.stringWidth(name) + 14, nameBaseline);
            }
        }

        private String clip(String s, FontMetrics fm, int width) {
            if (fm.stringWidth(s) <= width) {
                return s;
            }
            String e = "…";
            for (int i = s.length() - 1; i > 0; i--) {
                String t = e + s.substring(s.length() - i);
                if (fm.stringWidth(t) <= width) {
                    return t;
                }
            }
            return e;
        }
    }
}
