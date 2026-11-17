// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import io.github.logdelta.core.diff.InlineDiff;
import io.github.logdelta.core.diff.InlineHighlights;
import io.github.logdelta.core.diff.Span;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.model.RowKind;
import io.github.logdelta.core.normalize.Mask;
import io.github.logdelta.core.normalize.Rule;
import io.github.logdelta.core.report.LineMarkup;
import io.github.logdelta.core.timing.PhaseChange;
import io.github.logdelta.core.timing.Timeline;
import io.github.logdelta.core.timing.TimingAnalysis;
import io.github.logdelta.gui.model.DiffViewModel;
import io.github.logdelta.gui.model.SearchModel;
import io.github.logdelta.gui.model.ViewRow;
import io.github.logdelta.gui.theme.DiffPalette;

import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Paints the side-by-side comparison.
///
/// The component is as tall as all visual rows and exactly as wide as its viewport; horizontal
/// scrolling is done by painting with a shared offset, so both panes always scroll together.
/// Only rows inside the clip are painted, which keeps scrolling smooth for files with hundreds
/// of thousands of lines.
///
/// Each pane has a gutter (optional elapsed time, bookmark flag, line number), a marker column
/// (`-`, `+`, `~`, `≈` for expected variation, or a moved arrow) and the text. Text
/// decorations, from back to front: row tint, inline (word-level) change highlight, search
/// matches, syntax colours, masked-region styling.
///
/// With line wrapping on, a row is as tall as its longer side needs. Row positions are then
/// kept in a prefix-sum table that is rebuilt lazily whenever the model, the width, the font or
/// the divider changes.
public final class DiffCanvas extends JComponent implements Scrollable {

    /// Callbacks for user interaction.
    public interface Listener {
        /// The selected view row changed.
        default void selectionChanged(int viewIndex) {
        }

        /// The user asked for a context menu at `(x, y)` on `viewIndex`.
        default void contextMenuRequested(int viewIndex, boolean leftSide, int x, int y) {
        }

        /// A fold was clicked and expanded.
        default void foldExpanded() {
        }

        /// The divider between the panes moved; `split` is the left pane's share of the width.
        default void splitChanged(double split) {
        }

        /// A gutter was double-clicked: toggle a bookmark on that line.
        default void bookmarkToggleRequested(int viewIndex, boolean leftSide) {
        }

        /// A filter gap was clicked: the user wants to see the hidden rows.
        default void gapClicked() {
        }
    }

    /// Supplies the note of a bookmarked line, or `null` when the line has no bookmark.
    @FunctionalInterface
    public interface BookmarkLookup {
        /// No bookmarks.
        BookmarkLookup NONE = (_, _) -> null;

        /// The note for `lineNumber` on the given side, `""` for a bookmark without a note, or
        /// `null` if the line is not bookmarked.
        String note(boolean left, int lineNumber);
    }

    private static final int TAB = 4;
    private static final int MID = 10;
    private static final int PAD = 8;
    private static final int TIME_CHARS = 9;
    /// Extra pixels on each side of the divider that still grab it.
    private static final int GRAB = 4;
    /// Neither pane can be made narrower than this share of the width.
    public static final double MIN_SPLIT = 0.1;
    /// Largest share of the width the left pane can take.
    public static final double MAX_SPLIT = 0.9;

    private DiffViewModel model;
    private DiffPalette palette = DiffPalette.LIGHT;
    private Font font;
    private Font foldFont;
    private int rowHeight = 18;
    private int charWidth = 8;
    private int ascent = 13;
    private int hOffset;
    private boolean normalized;
    private boolean wrap;
    private boolean syntax = true;
    private boolean showTime;
    private BookmarkLookup bookmarks = BookmarkLookup.NONE;
    private int selected = -1;
    private SearchModel search = SearchModel.empty();
    private SearchModel.Match currentMatch;
    private double split = 0.5;
    private boolean draggingSplit;
    private boolean hoverSplit;
    private final Map<Integer, InlineHighlights> normalizedInline = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    // Wrapped layout: tops[v] is the y of view row v; tops[size] is the total height.
    private int[] tops;
    private Object layoutStamp;

    /// Creates an empty canvas.
    public DiffCanvas() {
        setFocusable(true);
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (e.getButton() == MouseEvent.BUTTON1 && !e.isPopupTrigger() && onDivider(e.getX())) {
                    if (e.getClickCount() == 2) {
                        setSplit(0.5);
                    } else {
                        draggingSplit = true;
                    }
                    return;
                }
                int v = rowAt(e.getY());
                if (v < 0) {
                    return;
                }
                if (e.isPopupTrigger()) {
                    select(v);
                    listeners.forEach(l -> l.contextMenuRequested(v, isLeft(e.getX()), e.getX(), e.getY()));
                    return;
                }
                if (e.getButton() == MouseEvent.BUTTON1 && model.get(v) instanceof ViewRow.Fold fold) {
                    model.expand(fold.start());
                    revalidate();
                    repaint();
                    listeners.forEach(Listener::foldExpanded);
                    select(v);
                    return;
                }
                if (e.getButton() == MouseEvent.BUTTON1 && model.get(v) instanceof ViewRow.Gap) {
                    listeners.forEach(Listener::gapClicked);
                    return;
                }
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2 && inGutter(e.getX())) {
                    listeners.forEach(l -> l.bookmarkToggleRequested(v, isLeft(e.getX())));
                }
                select(v);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (draggingSplit) {
                    draggingSplit = false;
                    updateDividerHover(e.getX());
                    return;
                }
                int v = rowAt(e.getY());
                if (v >= 0 && e.isPopupTrigger()) {
                    listeners.forEach(l -> l.contextMenuRequested(v, isLeft(e.getX()), e.getX(), e.getY()));
                }
            }
        };
        MouseAdapter divider = new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (draggingSplit && getWidth() > MID) {
                    setSplit((e.getX() - MID / 2.0) / (getWidth() - MID));
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                updateDividerHover(e.getX());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!draggingSplit) {
                    updateDividerHover(-1);
                }
            }
        };
        addMouseListener(mouse);
        addMouseListener(divider);
        addMouseMotionListener(divider);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (wrap) {
                    revalidate(); // the wrapped height depends on the width
                }
            }
        });
        setEditorFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    // --- configuration -------------------------------------------------------------------------

    /// Shows a new model (or nothing, if `null`).
    public void setModel(DiffViewModel model) {
        this.model = model;
        this.selected = -1;
        this.normalizedInline.clear();
        revalidate();
        repaint();
    }

    public DiffViewModel model() {
        return model;
    }

    public void setPalette(DiffPalette palette) {
        this.palette = palette;
        setBackground(palette.background());
        repaint();
    }

    public DiffPalette palette() {
        return palette;
    }

    /// Sets the monospaced font used for text and line numbers.
    public void setEditorFont(Font f) {
        this.font = f;
        this.foldFont = f.deriveFont(Font.ITALIC, f.getSize2D() - 1f);
        FontMetrics fm = getFontMetrics(f);
        this.charWidth = Math.max(1, fm.charWidth('m'));
        this.ascent = fm.getAscent();
        this.rowHeight = fm.getHeight() + 4;
        revalidate();
        repaint();
    }

    public Font editorFont() {
        return font;
    }

    /// Toggles between original and normalized text.
    public void setShowNormalized(boolean value) {
        this.normalized = value;
        revalidate();
        repaint();
    }

    public boolean isShowNormalized() {
        return normalized;
    }

    /// Turns line wrapping on or off. While wrapping, there is no horizontal scrolling.
    public void setWrap(boolean value) {
        if (value != wrap) {
            wrap = value;
            hOffset = 0;
            revalidate();
            repaint();
        }
    }

    public boolean isWrap() {
        return wrap;
    }

    /// Turns syntax colouring of timestamps, levels, threads and loggers on or off.
    public void setSyntaxColoring(boolean value) {
        syntax = value;
        repaint();
    }

    public boolean isSyntaxColoring() {
        return syntax;
    }

    /// Shows or hides the elapsed-time column (time since the first timestamp of each log).
    public void setShowElapsedTime(boolean value) {
        showTime = value;
        revalidate();
        repaint();
    }

    public boolean isShowElapsedTime() {
        return showTime;
    }

    /// Sets where bookmark flags are drawn.
    public void setBookmarks(BookmarkLookup lookup) {
        bookmarks = lookup == null ? BookmarkLookup.NONE : lookup;
        repaint();
    }

    /// Highlights search matches; `current` is emphasised.
    public void setSearch(SearchModel search, SearchModel.Match current) {
        this.search = search == null ? SearchModel.empty() : search;
        this.currentMatch = current;
        repaint();
    }

    /// The left pane's share of the width, between [#MIN_SPLIT] and [#MAX_SPLIT]; `0.5` means equal panes.
    public double split() {
        return split;
    }

    /// Moves the divider between the panes. Values outside the allowed range are clamped.
    public void setSplit(double value) {
        double v = Math.clamp(value, MIN_SPLIT, MAX_SPLIT);
        if (v != split) {
            split = v;
            if (wrap) {
                revalidate();
            }
            repaint();
            listeners.forEach(l -> l.splitChanged(split));
        }
    }

    private void updateDividerHover(int x) {
        boolean hover = x >= 0 && onDivider(x);
        if (hover != hoverSplit || draggingSplit) {
            hoverSplit = hover;
            setCursor(hover || draggingSplit ? Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR) : null);
            repaint(leftPaneWidth() - GRAB, 0, MID + 2 * GRAB, getHeight());
        }
    }

    /// Horizontal scroll offset in pixels, shared by both panes.
    public void setHorizontalOffset(int px) {
        int v = wrap ? 0 : Math.max(0, px);
        if (v != hOffset) {
            hOffset = v;
            repaint();
        }
    }

    // --- geometry ------------------------------------------------------------------------------

    /// Height of one text line in pixels (a wrapped row is a multiple of this).
    public int rowHeight() {
        return rowHeight;
    }

    /// Width of the visible text area of one pane.
    public int textAreaWidth() {
        return Math.max(0, Math.min(leftPaneWidth(), rightPaneWidth()) - gutterWidth() - markerWidth() - PAD);
    }

    /// Width in pixels of the longest line, after tab expansion.
    public int maxTextWidth() {
        if (model == null) {
            return 0;
        }
        int max = 0;
        DiffResult r = model.result();
        for (ProcessedLine l : r.leftLines()) {
            max = Math.max(max, displayLength(shown(l)));
        }
        for (ProcessedLine l : r.rightLines()) {
            max = Math.max(max, displayLength(shown(l)));
        }
        return max * charWidth + 2 * PAD;
    }

    private String shown(ProcessedLine l) {
        return normalized ? l.normalized() : l.text();
    }

    private int digits() {
        if (model == null) {
            return 3;
        }
        int lines = Math.max(model.result().leftFile().lineCount(), model.result().rightFile().lineCount());
        return Math.max(3, String.valueOf(lines).length());
    }

    private boolean timeColumnVisible() {
        return showTime && model != null && model.result().timing().available();
    }

    private int timeWidth() {
        return timeColumnVisible() ? TIME_CHARS * charWidth + PAD : 0;
    }

    private int gutterWidth() {
        return timeWidth() + digits() * charWidth + 2 * PAD + flagWidth();
    }

    private int flagWidth() {
        return Math.max(8, charWidth);
    }

    private int markerWidth() {
        return charWidth + 8;
    }

    /// Width of the left (baseline) pane in pixels.
    public int leftPaneWidth() {
        return Math.max(0, (int) Math.round((getWidth() - MID) * split));
    }

    /// X coordinate where the right (candidate) pane starts.
    public int rightPaneX() {
        return leftPaneWidth() + MID;
    }

    /// Width of the right (candidate) pane in pixels.
    public int rightPaneWidth() {
        return Math.max(0, getWidth() - rightPaneX());
    }

    private boolean onDivider(int x) {
        int left = leftPaneWidth();
        return x >= left - GRAB && x < left + MID + GRAB;
    }

    private boolean isLeft(int x) {
        return x < leftPaneWidth() + MID / 2;
    }

    private boolean inGutter(int x) {
        int x0 = isLeft(x) ? 0 : rightPaneX();
        return x >= x0 && x < x0 + gutterWidth() + markerWidth();
    }

    /// Columns of text that fit on one wrapped line of a pane `paneWidth` pixels wide.
    private int wrapColumns(int paneWidth) {
        return Math.max(8, (paneWidth - gutterWidth() - markerWidth() - 2 * PAD) / charWidth);
    }

    /// Rebuilds the wrapped layout if anything it depends on changed.
    private void ensureLayout() {
        if (!wrap || model == null) {
            tops = null;
            return;
        }
        List<Object> stamp = List.of(model, model.size(), model.filter(), getWidth(), split, charWidth, rowHeight,
                normalized, showTime);
        if (tops != null && stamp.equals(layoutStamp)) {
            return;
        }
        int n = model.size();
        int[] t = new int[n + 1];
        int leftCols = wrapColumns(leftPaneWidth());
        int rightCols = wrapColumns(rightPaneWidth());
        DiffResult r = model.result();
        int y = 0;
        for (int v = 0; v < n; v++) {
            t[v] = y;
            int lines = 1;
            if (model.get(v) instanceof ViewRow.Line(int rowIndex)) {
                DiffRow row = r.rows().get(rowIndex);
                lines = Math.max(sublines(r.left(row), leftCols), sublines(r.right(row), rightCols));
            }
            y += lines * rowHeight;
        }
        t[n] = y;
        tops = t;
        layoutStamp = stamp;
    }

    private int sublines(ProcessedLine line, int cols) {
        if (line == null) {
            return 1;
        }
        int len = displayLength(shown(line));
        return Math.max(1, (len + cols - 1) / cols);
    }

    /// Y coordinate of the top of view row `v`.
    public int rowTop(int v) {
        ensureLayout();
        return tops == null ? v * rowHeight : tops[Math.clamp(v, 0, tops.length - 1)];
    }

    /// Height in pixels of view row `v`.
    public int rowHeightOf(int v) {
        ensureLayout();
        return tops == null ? rowHeight : tops[v + 1] - tops[v];
    }

    private int totalHeight() {
        ensureLayout();
        int rows = model == null ? 0 : model.size();
        return tops == null ? rows * rowHeight : tops[rows];
    }

    /// View row at pixel `y`, or `-1`.
    public int rowAt(int y) {
        if (model == null || y < 0) {
            return -1;
        }
        ensureLayout();
        if (tops == null) {
            int v = y / rowHeight;
            return v < model.size() ? v : -1;
        }
        int i = Arrays.binarySearch(tops, 0, model.size(), y);
        int v = i >= 0 ? i : -i - 2;
        // Rows of zero height do not exist, so the last top <= y is the row.
        while (v + 1 < model.size() && tops[v + 1] == tops[v]) {
            v++;
        }
        return v >= 0 && v < model.size() && y < tops[model.size()] ? v : -1;
    }

    /// Bounds of a view row in component coordinates.
    public Rectangle rowBounds(int viewIndex) {
        return new Rectangle(0, rowTop(viewIndex), getWidth(), rowHeightOf(viewIndex));
    }

    // --- selection ------------------------------------------------------------------------------

    public int selected() {
        return selected;
    }

    /// Selects a view row and scrolls it into view.
    public void select(int viewIndex) {
        if (model == null || model.size() == 0) {
            return;
        }
        int v = Math.clamp(viewIndex, 0, model.size() - 1);
        int old = selected;
        selected = v;
        if (old >= 0 && old < model.size()) {
            repaint(rowBounds(old));
        }
        repaint(rowBounds(v));
        scrollRectToVisible(rowBounds(v));
        listeners.forEach(l -> l.selectionChanged(v));
    }

    /// Selects a view row and scrolls so that it appears about a third from the top.
    public void selectCentered(int viewIndex) {
        if (model == null || viewIndex < 0) {
            return;
        }
        Rectangle visible = getVisibleRect();
        int y = Math.max(0, rowTop(viewIndex) - visible.height / 3);
        scrollRectToVisible(new Rectangle(0, y, 1, visible.height));
        select(viewIndex);
    }

    // --- Scrollable ----------------------------------------------------------------------------

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(400, Math.max(1, totalHeight()));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(900, 600);
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? rowHeight : charWidth * 4;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? Math.max(rowHeight, visible.height - rowHeight)
                : visible.width / 2;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return model == null || totalHeight() < (getParent() == null ? 0 : getParent().getHeight());
    }

    // --- painting ------------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            Rectangle clip = g.getClipBounds();
            if (clip == null) {
                clip = new Rectangle(0, 0, getWidth(), getHeight());
            }
            g.setColor(palette.background());
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
            int pane = leftPaneWidth();
            g.setColor(palette.gutter());
            g.fillRect(pane, clip.y, MID, clip.height);
            if (model == null || model.size() == 0) {
                return;
            }
            g.setFont(font);
            int first = rowAt(Math.max(0, clip.y));
            if (first < 0) {
                return; // the clip lies below the last row
            }
            int lastY = clip.y + clip.height;
            for (int v = first; v < model.size(); v++) {
                int top = rowTop(v);
                if (top >= lastY) {
                    break;
                }
                paintRow(g, v, top, rowHeightOf(v), pane);
            }
            if (hoverSplit || draggingSplit) {
                Color c = palette.selectionBorder();
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), draggingSplit ? 160 : 90));
                g.fillRect(pane + 2, clip.y, MID - 4, clip.height);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintRow(Graphics2D g, int v, int y, int height, int pane) {
        switch (model.get(v)) {
            case ViewRow.Fold fold -> paintBand(g, String.format(Locale.ROOT,
                    "⋯   %,d unchanged line%s   ·   click to expand", fold.hiddenCount(),
                    fold.hiddenCount() == 1 ? "" : "s"), y, v == selected);
            case ViewRow.Gap gap -> paintBand(g, String.format(Locale.ROOT,
                    "⋯   %,d line%s hidden by the filter   ·   click to show all", gap.hiddenCount(),
                    gap.hiddenCount() == 1 ? "" : "s"), y, v == selected);
            case ViewRow.Line(int rowIndex) -> paintLine(g, rowIndex, v, y, height, pane);
        }
    }

    private void paintLine(Graphics2D g, int rowIndex, int v, int y, int height, int pane) {
        DiffResult result = model.result();
        DiffRow row = result.rows().get(rowIndex);
        paintSide(g, rowIndex, row, result.left(row), true, 0, pane, y, height);
        paintSide(g, rowIndex, row, result.right(row), false, pane + MID, getWidth() - pane - MID, y, height);
        g.setColor(palette.divider());
        g.drawLine(pane, y, pane, y + height - 1);
        g.drawLine(pane + MID - 1, y, pane + MID - 1, y + height - 1);
        if (row.isMoved()) {
            g.setColor(palette.movedMarker());
            int cx = pane + MID / 2;
            g.fillOval(cx - 2, y + rowHeight / 2 - 2, 4, 4);
        }
        TimingAnalysis timing = result.timing();
        if (timing.available() && timing.slowPhaseAt(rowIndex) != null) {
            g.setColor(palette.slowPhase());
            g.fillRect(pane + MID, y, 3, height);
        }
        if (v == selected) {
            g.setColor(palette.selectionBorder());
            g.setStroke(new BasicStroke(1f));
            g.drawRect(0, y, getWidth() - 1, height - 1);
        }
    }

    private void paintBand(Graphics2D g, String label, int y, boolean isSelected) {
        g.setColor(isSelected ? palette.selection() : palette.fold());
        g.fillRect(0, y, getWidth(), rowHeight);
        g.setColor(palette.divider());
        g.drawLine(0, y, getWidth(), y);
        g.drawLine(0, y + rowHeight - 1, getWidth(), y + rowHeight - 1);
        g.setFont(foldFont);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(palette.foldText());
        g.drawString(label, (getWidth() - fm.stringWidth(label)) / 2, y + (rowHeight - fm.getHeight()) / 2
                + fm.getAscent());
        g.setFont(font);
    }

    private void paintSide(Graphics2D g, int rowIndex, DiffRow row, ProcessedLine line, boolean left, int x0,
                           int width, int y, int height) {
        int gutter = gutterWidth();
        int marker = markerWidth();
        int textX = x0 + gutter + marker;
        int textW = width - gutter - marker;
        int baseline = y + (rowHeight - (ascent + getFontMetrics(font).getDescent())) / 2 + ascent;

        // Gutter.
        g.setColor(palette.gutter());
        g.fillRect(x0, y, gutter, height);

        if (line == null) {
            g.setColor(palette.empty());
            g.fillRect(x0 + gutter, y, width - gutter, height);
            Shape oldClip = g.getClip();
            g.clipRect(x0 + gutter, y, width - gutter, height);
            g.setColor(palette.emptyHatch());
            for (int hx = x0 + gutter - height - (y % 8); hx < x0 + width; hx += 8) {
                g.drawLine(hx, y + height, hx + height, y);
            }
            g.setClip(oldClip);
            return;
        }

        // Row tint; normal variation that another baseline explains is drawn faintly.
        Color tint = tint(row, left);
        if (tint != null) {
            g.setColor(row.expected() ? faint(tint) : tint);
            g.fillRect(x0 + gutter, y, width - gutter, height);
        }

        // Elapsed time.
        if (timeColumnVisible()) {
            Timeline timeline = left ? model.result().timing().left() : model.result().timing().right();
            int index = left ? row.left() : row.right();
            if (index >= 0 && index < timeline.size() && timeline.hasOwnTimestamp(index)) {
                String t = Timeline.formatOffset(timeline.offset(index));
                g.setColor(palette.syntax().timestamp());
                g.drawString(t, x0 + PAD / 2 + (TIME_CHARS - t.length()) * charWidth, baseline);
            }
        }

        // Bookmark flag.
        String note = bookmarks.note(left, line.number());
        if (note != null) {
            paintFlag(g, x0 + timeWidth() + 3, y + 3, flagWidth() - 2, rowHeight - 6);
        }

        // Line number.
        String number = String.valueOf(line.number());
        g.setColor(palette.lineNumber());
        g.drawString(number, x0 + gutter - PAD - number.length() * charWidth, baseline);

        // Marker.
        if (row.isChange()) {
            String m = row.isMoved() ? "↕" : row.expected() ? "≈" : switch (row.kind()) {
                case REMOVED -> "−";
                case ADDED -> "+";
                default -> "~";
            };
            g.setColor(markerColor(row, left));
            g.drawString(m, x0 + gutter + (marker - g.getFontMetrics().stringWidth(m)) / 2, baseline);
        }

        // Text, possibly wrapped over several visual lines.
        if (wrap) {
            int cols = wrapColumns(width);
            int lines = Math.max(1, height / rowHeight);
            // Clip each visual line to exactly `cols` characters, so the start of the next
            // segment never shows at the right edge.
            int clipW = Math.min(textW, PAD + cols * charWidth);
            for (int k = 0; k < lines; k++) {
                paintText(g, rowIndex, row, line, left, textX, clipW, y + k * rowHeight, baseline + k * rowHeight,
                        k * cols * charWidth);
            }
        } else {
            paintText(g, rowIndex, row, line, left, textX, textW, y, baseline, hOffset);
        }
    }

    private void paintText(Graphics2D g, int rowIndex, DiffRow row, ProcessedLine line, boolean left, int textX,
                           int textW, int y, int baseline, int offset) {
        String text = shown(line);
        List<Mask> masks = normalized ? placeholderMasks(line) : line.masks();
        List<Span> highlights = highlights(rowIndex, row, left);
        int[] cols = columns(text);
        String display = expand(text);
        int firstCol = offset / charWidth;
        int lastCol = firstCol + textW / charWidth + 2;
        int originX = textX + PAD - offset;
        byte[] styles = syntax && !normalized ? SyntaxHighlighter.styles(text) : null;

        Shape oldClip = g.getClip();
        g.clipRect(textX, y, textW, rowHeight);

        Color strong = left ? palette.removedStrong() : palette.addedStrong();
        for (Span s : highlights) {
            fillColumns(g, cols, s.start(), s.end(), originX, y, strong, text.length());
        }
        for (SearchModel.Match m : search.matchesInRow(rowIndex)) {
            if (m.left() == left) {
                Color c = m.equals(currentMatch) ? palette.searchCurrent() : palette.searchMatch();
                fillColumns(g, cols, m.start(), m.end(), originX, y, c, text.length());
            }
        }
        LineMarkup.runs(text, masks, List.of(), run -> {
            if (run.mask() != null) {
                drawChunk(g, display, cols[run.start()], cols[run.end()], firstCol, lastCol, originX, baseline,
                        palette.maskText(), true);
            } else if (styles == null) {
                drawChunk(g, display, cols[run.start()], cols[run.end()], firstCol, lastCol, originX, baseline,
                        palette.text(), false);
            } else {
                int i = run.start();
                while (i < run.end()) {
                    int j = i + 1;
                    while (j < run.end() && styles[j] == styles[i]) {
                        j++;
                    }
                    drawChunk(g, display, cols[i], cols[j], firstCol, lastCol, originX, baseline,
                            styleColor(styles[i]), false);
                    i = j;
                }
            }
        });
        g.setClip(oldClip);
    }

    private void drawChunk(Graphics2D g, String display, int c0, int c1, int firstCol, int lastCol, int originX,
                           int baseline, Color color, boolean masked) {
        if (c1 < firstCol || c0 > lastCol) {
            return;
        }
        int from = Math.max(c0, firstCol);
        int to = Math.min(c1, Math.min(display.length(), lastCol));
        if (to <= from) {
            return;
        }
        int x = originX + from * charWidth;
        String chunk = display.substring(from, to);
        g.setColor(color);
        g.drawString(chunk, x, baseline);
        if (masked) {
            g.setColor(palette.maskUnderline());
            int uy = baseline + 2;
            for (int dx = x; dx < x + chunk.length() * charWidth; dx += 3) {
                g.drawLine(dx, uy, dx, uy);
            }
        }
    }

    private Color styleColor(byte style) {
        DiffPalette.Syntax s = palette.syntax();
        return switch (style) {
            case SyntaxHighlighter.TIMESTAMP -> s.timestamp();
            case SyntaxHighlighter.LEVEL_ERROR -> s.error();
            case SyntaxHighlighter.LEVEL_WARN -> s.warn();
            case SyntaxHighlighter.LEVEL_INFO -> s.info();
            case SyntaxHighlighter.LEVEL_DEBUG -> s.debug();
            case SyntaxHighlighter.LOGGER -> s.logger();
            case SyntaxHighlighter.THREAD -> s.thread();
            case SyntaxHighlighter.STACK -> s.stack();
            default -> palette.text();
        };
    }

    private void paintFlag(Graphics2D g, int x, int y, int w, int h) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(palette.bookmark());
        java.awt.geom.Path2D flag = new java.awt.geom.Path2D.Float();
        flag.moveTo(x, y);
        flag.lineTo(x + w, y);
        flag.lineTo(x + w, y + h);
        flag.lineTo(x + w / 2.0, y + h - Math.max(2, h / 4.0));
        flag.lineTo(x, y + h);
        flag.closePath();
        g2.fill(flag);
        g2.dispose();
    }

    private static Color faint(Color c) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), 90);
    }

    private void fillColumns(Graphics2D g, int[] cols, int start, int end, int originX, int y, Color c, int len) {
        int s = cols[Math.min(start, len)];
        int e = cols[Math.min(end, len)];
        if (e <= s) {
            return;
        }
        g.setColor(c);
        g.fillRoundRect(originX + s * charWidth - 1, y + 2, (e - s) * charWidth + 2, rowHeight - 4, 4, 4);
    }

    private Color tint(DiffRow row, boolean left) {
        if (row.isMoved()) {
            return palette.moved();
        }
        return switch (row.kind()) {
            case REMOVED -> palette.removed();
            case ADDED -> palette.added();
            case MODIFIED -> left ? palette.removed() : palette.added();
            case UNCHANGED -> null;
        };
    }

    private Color markerColor(DiffRow row, boolean left) {
        if (row.isMoved()) {
            return palette.movedMarker();
        }
        if (row.expected()) {
            return palette.lineNumber();
        }
        if (row.kind() == RowKind.MODIFIED) {
            return palette.modifiedMarker();
        }
        return left ? palette.removedMarker() : palette.addedMarker();
    }

    private List<Span> highlights(int rowIndex, DiffRow row, boolean left) {
        if (row.kind() != RowKind.MODIFIED) {
            return List.of();
        }
        InlineHighlights h;
        if (normalized) {
            h = normalizedInline.computeIfAbsent(rowIndex, _ -> {
                DiffResult r = model.result();
                return InlineDiff.compute(r.left(row).normalized(), r.right(row).normalized());
            });
        } else {
            h = row.inline();
        }
        return left ? h.left() : h.right();
    }

    /// Masks in normalized-text coordinates: each covers the replacement text.
    static List<Mask> placeholderMasks(ProcessedLine line) {
        if (line.masks().isEmpty()) {
            return List.of();
        }
        List<Mask> out = new ArrayList<>(line.masks().size());
        int delta = 0;
        for (Mask m : line.masks()) {
            int start = m.start() + delta;
            out.add(new Mask(start, start + m.replacement().length(), m.replacement(), m.ruleId()));
            delta += m.replacement().length() - m.length();
        }
        return out;
    }

    // --- tabs ----------------------------------------------------------------------------------

    /// Display column of every character offset (length + 1 entries), expanding tabs.
    static int[] columns(String s) {
        int[] cols = new int[s.length() + 1];
        int col = 0;
        for (int i = 0; i < s.length(); i++) {
            cols[i] = col;
            col += s.charAt(i) == '\t' ? TAB - (col % TAB) : 1;
        }
        cols[s.length()] = col;
        return cols;
    }

    private static String expand(String s) {
        return io.github.logdelta.core.util.Text.expandTabs(s, TAB);
    }

    private static int displayLength(String s) {
        return s.indexOf('\t') < 0 ? s.length() : columns(s)[s.length()];
    }

    // --- tooltips ------------------------------------------------------------------------------

    @Override
    public String getToolTipText(MouseEvent e) {
        if (onDivider(e.getX())) {
            return "Drag to resize the panes · double-click for equal widths";
        }
        int v = rowAt(e.getY());
        if (v < 0 || !(model.get(v) instanceof ViewRow.Line)) {
            return null;
        }
        DiffResult result = model.result();
        DiffRow row = model.diffRow(v);
        boolean left = isLeft(e.getX());
        ProcessedLine line = left ? result.left(row) : result.right(row);
        int x0 = left ? 0 : rightPaneX();
        if (!left && e.getX() < x0 + 4 && result.timing().available()) {
            PhaseChange p = result.timing().slowPhaseAt(model.get(v).firstRow());
            if (p != null) {
                return String.format(Locale.ROOT, "<html><b>Slower phase</b>: %s here, %s in the baseline "
                                + "(%.1f×)<br>from <code>%s</code><br>to <code>%s</code></html>",
                        Timeline.format(p.rightMillis()), Timeline.format(p.leftMillis()), p.factor(),
                        escape(io.github.logdelta.core.util.Text.truncate(p.startText().strip(), 90)),
                        escape(io.github.logdelta.core.util.Text.truncate(p.endText().strip(), 90)));
            }
        }
        if (line == null) {
            return null;
        }
        int gutter = gutterWidth();
        if (e.getX() < x0 + gutter) {
            String note = bookmarks.note(left, line.number());
            if (note != null) {
                return note.isBlank() ? "Bookmark · double-click to remove"
                        : "<html><b>Bookmark</b><br>" + escape(note).replace("\n", "<br>") + "</html>";
            }
            return "Double-click to bookmark this line";
        }
        int markerX = x0 + gutter;
        if (e.getX() >= markerX && e.getX() < markerX + markerWidth()) {
            if (row.isMoved()) {
                DiffRow peer = result.rows().get(row.movedPeer());
                ProcessedLine other = peer.hasLeft() ? result.left(peer) : result.right(peer);
                return (row.kind() == RowKind.REMOVED ? "Moved to candidate line " : "Moved from baseline line ")
                        + other.number();
            }
            if (row.expected()) {
                return "Normal variation: another baseline run differs in the same way";
            }
        }
        if (normalized) {
            return null;
        }
        int textX = markerX + markerWidth() + PAD - hOffset;
        int col = (e.getX() - textX) / charWidth;
        if (wrap) {
            int sub = (e.getY() - rowTop(v)) / rowHeight;
            col = Math.max(0, e.getX() - (markerX + markerWidth() + PAD)) / charWidth
                    + sub * wrapColumns(left ? leftPaneWidth() : rightPaneWidth());
        }
        int[] cols = columns(line.text());
        for (Mask m : line.masks()) {
            if (col >= cols[m.start()] && col < cols[m.end()]) {
                Rule rule = result.options().rules().find(m.ruleId());
                String name = rule == null ? m.ruleId() : rule.name();
                return "<html><b>" + escape(name) + "</b> masked <code>"
                        + escape(line.text().substring(m.start(), m.end())) + "</code> as <code>"
                        + escape(m.replacement()) + "</code></html>";
            }
        }
        return null;
    }

    private static String escape(String s) {
        return io.github.logdelta.core.util.Text.escapeHtml(s);
    }

    /// Bookmark keys for a set of `(side, line)` pairs, for callers that keep bookmarks in a set.
    public static long key(boolean left, int lineNumber) {
        return (left ? 0L : 1L << 32) | (lineNumber & 0xFFFFFFFFL);
    }

    /// A [BookmarkLookup] backed by a map from [#key] to note.
    public static BookmarkLookup lookup(Map<Long, String> notes) {
        return (left, line) -> notes.get(key(left, line));
    }
}
