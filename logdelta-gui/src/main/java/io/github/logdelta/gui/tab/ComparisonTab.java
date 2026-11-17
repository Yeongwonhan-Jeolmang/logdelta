// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.tab;

import io.github.logdelta.core.config.ProfileIO;
import io.github.logdelta.core.config.ProfileParseException;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.io.LogFile;
import io.github.logdelta.core.io.LogSources;
import io.github.logdelta.core.model.DiffRow;
import io.github.logdelta.core.model.ProcessedLine;
import io.github.logdelta.core.normalize.RuleSet;
import io.github.logdelta.core.report.ReportFormat;
import io.github.logdelta.core.report.ReportOptions;
import io.github.logdelta.core.report.Reports;
import io.github.logdelta.core.session.Bookmark;
import io.github.logdelta.core.session.Session;
import io.github.logdelta.core.session.SessionIO;
import io.github.logdelta.gui.controller.ComparisonController;
import io.github.logdelta.gui.dialog.RuleEditorDialog;
import io.github.logdelta.gui.model.Source;
import io.github.logdelta.gui.sidebar.InsightsPanel;
import io.github.logdelta.gui.sidebar.NotesPanel;
import io.github.logdelta.gui.sidebar.RulesPanel;
import io.github.logdelta.gui.sidebar.SummaryPanel;
import io.github.logdelta.gui.sidebar.TimingPanel;
import io.github.logdelta.gui.theme.DiffPalette;
import io.github.logdelta.gui.theme.Theme;
import io.github.logdelta.gui.util.FileWatcher;
import io.github.logdelta.gui.util.IgnorePatterns;
import io.github.logdelta.gui.view.DiffCanvas;
import io.github.logdelta.gui.view.DiffView;
import io.github.logdelta.gui.view.FileBar;
import io.github.logdelta.gui.view.WelcomePanel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// One comparison: its sources and options, the diff view, the sidebar, bookmarks and notes, and
/// live-tail state.
public final class ComparisonTab extends JPanel implements AppTab, ComparisonController.Listener {

    private static final String CARD_WELCOME = "welcome";
    private static final String CARD_DIFF = "diff";
    /// How often URL sources are re-fetched in live-tail mode.
    private static final int URL_POLL_MS = 5000;

    private final TabHost host;
    private final ComparisonController controller;
    private final DiffView diffView = new DiffView();
    private final FileBar fileBar;
    private final WelcomePanel welcome;
    private final SummaryPanel summary;
    private final InsightsPanel insights;
    private final TimingPanel timing;
    private final RulesPanel rulesPanel;
    private final NotesPanel notesPanel;
    private final JTabbedPane sidebar = new JTabbedPane();
    private final JSplitPane split;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final FileWatcher watcher;
    private final Timer urlPoll;

    private boolean collapse;
    private int context;
    private boolean liveTail;
    private boolean followAfterReload;
    private boolean remembered;
    private boolean reloadPending;

    private List<Bookmark> bookmarks = new ArrayList<>();
    private final Map<Long, String> bookmarkNotes = new HashMap<>();
    private String notes = "";
    private Path sessionFile;
    private boolean dirty;

    private String statusMessage = "Ready";
    private String statusDetails = "";
    private boolean busy;
    private String busyStage = "";
    private double busyFraction = -1;

    /// @param host    the main window
    /// @param options options to start with
    /// @param welcomeLinks extra ways to start shown on the empty tab (label to action)
    public ComparisonTab(TabHost host, DiffOptions options, Map<String, Runnable> welcomeLinks) {
        super(new BorderLayout());
        this.host = host;
        this.collapse = host.settings().collapseUnchanged();
        this.context = host.settings().contextLines();
        this.controller = new ComparisonController(options);
        this.controller.addListener(this);
        this.watcher = new FileWatcher(this::sourcesChangedOnDisk);
        this.urlPoll = new Timer(URL_POLL_MS, _ -> sourcesChangedOnDisk());

        fileBar = new FileBar(controller::setLeft, controller::setRight, () -> browse(true), () -> browse(false),
                controller::swap, this::showBaselinesMenu);
        summary = new SummaryPanel(diffView::goToRow);
        insights = new InsightsPanel(diffView::goToRow);
        timing = new TimingPanel(diffView::goToRow);
        rulesPanel = new RulesPanel(controller::setOptions, () -> editRules(null));
        notesPanel = new NotesPanel(new NotesPanel.Listener() {
            @Override
            public void goTo(Bookmark b) {
                goToBookmark(b);
            }

            @Override
            public void edit(Bookmark b) {
                editNote(b);
            }

            @Override
            public void remove(Bookmark b) {
                List<Bookmark> next = new ArrayList<>(bookmarks);
                next.remove(b);
                setBookmarks(next);
            }

            @Override
            public void notesChanged(String text) {
                notes = text;
                markDirty();
            }
        });
        welcome = new WelcomePanel(this::openBoth, this::openExample,
                p -> controller.open(p.leftSource(), p.rightSource()), welcomeLinks);

        sidebar.addTab("Summary", summary);
        sidebar.addTab("Insights", insights);
        sidebar.addTab("Timing", timing);
        sidebar.addTab("Rules", rulesPanel);
        sidebar.addTab("Notes", notesPanel);
        sidebar.setMinimumSize(new Dimension(240, 100));
        sidebar.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        sidebar.setName("tour.sidebar");

        center.add(welcome, CARD_WELCOME);
        center.add(diffView, CARD_DIFF);
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, sidebar);
        split.setResizeWeight(1.0);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);

        add(fileBar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        diffView.canvas().addListener(new DiffCanvas.Listener() {
            @Override
            public void contextMenuRequested(int viewIndex, boolean leftSide, int x, int y) {
                showContextMenu(leftSide, x, y);
            }

            @Override
            public void splitChanged(double value) {
                host.settings().setPaneSplit(value);
            }

            @Override
            public void bookmarkToggleRequested(int viewIndex, boolean leftSide) {
                toggleBookmark(leftSide);
            }
        });
        diffView.canvas().setSplit(host.settings().paneSplit());
        diffView.canvas().setBookmarks(DiffCanvas.lookup(bookmarkNotes));
        rulesPanel.setOptions(controller.options());
        welcome.setRecent(host.settings().recent());
        SwingUtilities.invokeLater(() -> split.setDividerLocation(Math.max(300,
                split.getWidth() - host.settings().sidebarWidth())));
    }

    // --- AppTab --------------------------------------------------------------------------------

    @Override
    public JComponent component() {
        return this;
    }

    @Override
    public String title() {
        if (controller.left() == null && controller.right() == null) {
            return "New comparison";
        }
        String l = controller.left() == null ? "?" : controller.left().displayName();
        String r = controller.right() == null ? "?" : controller.right().displayName();
        return (liveTail ? "● " : "") + l + " ↔ " + r + (dirty ? " *" : "");
    }

    @Override
    public String tooltip() {
        StringBuilder sb = new StringBuilder("<html>");
        if (controller.left() != null) {
            sb.append("Baseline: ").append(esc(controller.left().description())).append("<br>");
        }
        for (Source s : controller.extraBaselines()) {
            sb.append("Also: ").append(esc(s.description())).append("<br>");
        }
        if (controller.right() != null) {
            sb.append("Candidate: ").append(esc(controller.right().description())).append("<br>");
        }
        if (sessionFile != null) {
            sb.append("Session: ").append(esc(sessionFile.toString())).append("<br>");
        }
        if (liveTail) {
            sb.append("<i>Live tail: following changes</i>");
        }
        return sb.append("</html>").toString();
    }

    @Override
    public String statusMessage() {
        return statusMessage;
    }

    @Override
    public String statusDetails() {
        return statusDetails;
    }

    @Override
    public boolean isBusy() {
        return busy;
    }

    @Override
    public String busyStage() {
        return busyStage;
    }

    @Override
    public double busyFraction() {
        return busyFraction;
    }

    @Override
    public void cancel() {
        controller.cancel();
    }

    @Override
    public boolean confirmClose() {
        if (!dirty || bookmarks.isEmpty() && notes.isBlank()) {
            return true;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "This comparison has bookmarks or notes that are not saved.\nSave them as a session first?",
                "Close comparison", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
            return false;
        }
        return answer == JOptionPane.NO_OPTION || saveSession(false);
    }

    @Override
    public void dispose() {
        watcher.close();
        urlPoll.stop();
        controller.cancel();
        host.settings().setSidebarWidth(split.getWidth() - split.getDividerLocation());
    }

    // --- accessors used by the window ------------------------------------------------------------

    /// The comparison state.
    public ComparisonController controller() {
        return controller;
    }

    /// The comparison view.
    public DiffView diffView() {
        return diffView;
    }

    /// The latest result, or `null`.
    public DiffResult result() {
        return controller.result();
    }

    /// Whether this tab has nothing open yet, so it can be reused.
    public boolean isEmpty() {
        return controller.left() == null && controller.right() == null && bookmarks.isEmpty() && notes.isBlank();
    }

    /// Whether live tail is on.
    public boolean isLiveTail() {
        return liveTail;
    }

    // --- controller events ---------------------------------------------------------------------

    @Override
    public void filesChanged(Source left, Source right) {
        remembered = false;
        fileBar.setSources(left, right);
        updateWatching();
        host.tabChanged(this);
    }

    @Override
    public void baselinesChanged(List<Source> extraBaselines) {
        fileBar.setBaselineCount(extraBaselines.size());
        updateWatching();
        host.tabChanged(this);
    }

    @Override
    public void optionsChanged(DiffOptions options) {
        rulesPanel.setOptions(options);
        host.settings().saveSessionOptions(options);
        host.tabChanged(this);
    }

    @Override
    public void comparisonStarted() {
        busy = true;
        busyStage = "Comparing";
        busyFraction = -1;
        // Follow the end if live tail was just switched on, or the user is already at the end.
        followAfterReload = liveTail && (followAfterReload || controller.result() == null || diffView.isAtEnd());
        host.tabChanged(this);
    }

    @Override
    public void progress(String stage, double fraction) {
        busyStage = stage;
        busyFraction = fraction;
        host.tabChanged(this);
    }

    @Override
    public void comparisonFinished(DiffResult result, boolean refresh) {
        busy = false;
        cards.show(center, CARD_DIFF);
        relocateBookmarks(result);
        diffView.show(result, collapse, context, refresh);
        summary.show(result);
        insights.show(result);
        timing.show(result);
        rulesPanel.setSuggestions(result.suggestions());
        int problems = result.insights().newProblems().size();
        sidebar.setTitleAt(1, problems == 0 ? "Insights" : "Insights (" + problems + ")");
        int slower = timing.slowerCount();
        sidebar.setTitleAt(2, slower == 0 ? "Timing" : "Timing (" + slower + ")");
        sidebar.setTitleAt(3, result.suggestions().isEmpty() ? "Rules" : "Rules •");
        var s = result.stats();
        statusMessage = s.identical() ? (s.expected() > 0 ? "Only normal variation ("
                + s.expected() + " differences other baselines share)" : "No differences after normalization")
                : String.format(Locale.ROOT, "%,d modified · %,d removed · %,d added · %,d moved%s",
                s.modified(), s.removed() - s.moved(), s.added() - s.moved(), s.moved(),
                s.expected() > 0 ? " · " + s.expected() + " normal variation" : "");
        statusDetails = String.format(Locale.ROOT, "%s · %d ms · %s / %s",
                result.options().algorithm().displayName(), result.elapsedMillis(),
                result.leftFile().charset().name(), result.rightFile().charset().name());
        if (!remembered && !refresh) {
            remembered = true;
            host.remember(controller.left(), controller.right());
            if (!s.identical() && !liveTail) {
                SwingUtilities.invokeLater(diffView::nextChange);
            }
        }
        if (followAfterReload) {
            diffView.scrollToEnd();
            followAfterReload = false;
        }
        host.tabChanged(this);
        if (!refresh) {
            diffView.canvas().requestFocusInWindow();
        }
        runPendingReload();
    }

    @Override
    public void comparisonFailed(String message) {
        busy = false;
        statusMessage = message == null ? "Comparison cancelled" : message;
        host.tabChanged(this);
        if (message != null && !liveTail) {
            JOptionPane.showMessageDialog(this, message, "Cannot compare", JOptionPane.ERROR_MESSAGE);
        }
        runPendingReload();
    }

    // --- opening ---------------------------------------------------------------------------------

    /// Compares two sources.
    public void open(Source left, Source right) {
        remembered = false;
        controller.open(left, right);
    }

    /// Asks for one or two files.
    public void openBoth() {
        JFileChooser chooser = host.chooser("Choose the baseline and candidate logs (select two files)");
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] files = chooser.getSelectedFiles();
        host.settings().setLastDirectory(chooser.getCurrentDirectory().toPath());
        if (files.length >= 2) {
            open(Source.of(files[0].toPath()), Source.of(files[1].toPath()));
        } else if (files.length == 1) {
            if (controller.left() == null) {
                controller.setLeft(Source.of(files[0].toPath()));
                browse(false);
            } else {
                controller.setRight(Source.of(files[0].toPath()));
            }
        }
    }

    /// Asks for the baseline (`left`) or the candidate file.
    public void browse(boolean left) {
        JFileChooser chooser = host.chooser(left ? "Choose the baseline log" : "Choose the candidate log");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            host.settings().setLastDirectory(chooser.getCurrentDirectory().toPath());
            Source s = Source.of(chooser.getSelectedFile().toPath());
            if (left) {
                controller.setLeft(s);
            } else {
                controller.setRight(s);
            }
        }
    }

    /// Handles files dropped onto the tab.
    public void dropped(List<Path> files) {
        if (files.size() >= 3) {
            List<Source> extras = new ArrayList<>();
            for (Path p : files.subList(1, files.size() - 1)) {
                extras.add(Source.of(p));
            }
            controller.setExtraBaselines(extras);
            open(Source.of(files.getFirst()), Source.of(files.getLast()));
        } else if (files.size() == 2) {
            open(Source.of(files.getFirst()), Source.of(files.get(1)));
        } else if (controller.left() == null) {
            controller.setLeft(Source.of(files.getFirst()));
        } else {
            controller.setRight(Source.of(files.getFirst()));
        }
    }

    /// Uses the clipboard text as the baseline (`left`) or the candidate.
    public void paste(boolean left) {
        String text;
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            text = data == null ? "" : data.toString();
        } catch (Exception _) {
            text = "";
        }
        if (text.isBlank()) {
            JOptionPane.showMessageDialog(this, "The clipboard does not contain text.", "Paste log",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String name = "Pasted " + (left ? "baseline" : "candidate") + " "
                + java.time.LocalTime.now().withNano(0);
        Source s = new Source.Text(LogFile.ofText(name, text));
        if (left) {
            controller.setLeft(s);
        } else {
            controller.setRight(s);
        }
        statusMessage = "Pasted " + s.description() + " as the " + (left ? "baseline" : "candidate");
        host.tabChanged(this);
    }

    /// Opens the bundled example, including a second baseline run.
    public void openExample() {
        try {
            Path dir = Files.createTempDirectory("logdelta-example");
            dir.toFile().deleteOnExit();
            Path left = copyResource("baseline.log", dir);
            Path extra = copyResource("baseline-2.log", dir);
            Path right = copyResource("candidate.log", dir);
            Path profile = copyResource("checkout.ldprofile", dir);
            controller.setOptions(ProfileIO.read(profile));
            controller.setExtraBaselines(List.of(Source.of(extra)));
            open(Source.of(left), Source.of(right));
            statusMessage = "Loaded the example with its profile and a second known-good run";
            host.tabChanged(this);
        } catch (IOException | ProfileParseException e) {
            JOptionPane.showMessageDialog(this, "Cannot load the example: " + e.getMessage(), "Example",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static Path copyResource(String name, Path dir) throws IOException {
        try (InputStream in = ComparisonTab.class.getResourceAsStream("/io/github/logdelta/gui/examples/" + name)) {
            if (in == null) {
                throw new IOException("missing resource " + name);
            }
            Path target = dir.resolve(name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().deleteOnExit();
            return target;
        }
    }

    // --- extra baselines -----------------------------------------------------------------------

    private void showBaselinesMenu(JButton anchor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem addFile = new JMenuItem("Add Baseline Run…");
        addFile.addActionListener(_ -> addBaseline());
        menu.add(addFile);
        JMenuItem addUrl = new JMenuItem("Add Baseline from URL…");
        addUrl.addActionListener(_ -> {
            String url = JOptionPane.showInputDialog(this, "URL of another known-good run:", "Add baseline",
                    JOptionPane.PLAIN_MESSAGE);
            if (url != null && !url.isBlank()) {
                try {
                    List<Source> next = new ArrayList<>(controller.extraBaselines());
                    next.add(Source.parse(url));
                    controller.setExtraBaselines(next);
                } catch (IllegalArgumentException e) {
                    JOptionPane.showMessageDialog(this, e.getMessage(), "Add baseline", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        menu.add(addUrl);
        if (!controller.extraBaselines().isEmpty()) {
            menu.addSeparator();
            for (Source s : controller.extraBaselines()) {
                JMenuItem remove = new JMenuItem("Remove " + s.displayName());
                remove.setToolTipText(s.description());
                remove.addActionListener(_ -> {
                    List<Source> next = new ArrayList<>(controller.extraBaselines());
                    next.remove(s);
                    controller.setExtraBaselines(next);
                });
                menu.add(remove);
            }
            menu.addSeparator();
            JMenuItem clear = new JMenuItem("Remove All Extra Baselines");
            clear.addActionListener(_ -> controller.setExtraBaselines(List.of()));
            menu.add(clear);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    /// Asks for one or more further known-good runs.
    public void addBaseline() {
        JFileChooser chooser = host.chooser("Choose other known-good runs");
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        host.settings().setLastDirectory(chooser.getCurrentDirectory().toPath());
        List<Source> next = new ArrayList<>(controller.extraBaselines());
        for (File f : chooser.getSelectedFiles()) {
            next.add(Source.of(f.toPath()));
        }
        controller.setExtraBaselines(next);
    }

    // --- live tail -------------------------------------------------------------------------------

    /// Turns live tail on or off: re-compare whenever a source changes (files are watched, URLs
    /// polled) and keep the view at the end, like `tail -f`.
    public void setLiveTail(boolean on) {
        liveTail = on;
        updateWatching();
        if (on) {
            followAfterReload = true;
            controller.reload();
            statusMessage = "Live tail: following " + (controller.right() == null ? "the candidate"
                    : controller.right().displayName());
        } else {
            statusMessage = "Live tail off";
        }
        host.tabChanged(this);
    }

    /// Starts or stops watching according to the settings and live-tail state.
    public void updateWatching() {
        boolean watch = (liveTail || host.settings().followChanges()) && controller.hasFiles();
        Set<Path> files = new HashSet<>();
        boolean urls = false;
        if (watch) {
            List<Source> all = new ArrayList<>(controller.extraBaselines());
            all.add(controller.left());
            all.add(controller.right());
            for (Source s : all) {
                if (s.path() != null) {
                    files.add(s.path());
                } else if (s instanceof Source.Url) {
                    urls = true;
                }
            }
        }
        try {
            if (files.isEmpty()) {
                watcher.stop();
            } else {
                watcher.watch(files);
            }
        } catch (IOException e) {
            statusMessage = "Cannot watch files: " + e.getMessage();
            host.tabChanged(this);
        }
        if (watch && urls && liveTail) {
            urlPoll.start();
        } else {
            urlPoll.stop();
        }
    }

    private void sourcesChangedOnDisk() {
        if (controller.isBusy()) {
            reloadPending = true; // compare again once the running comparison is done
        } else {
            controller.reload();
        }
    }

    private void runPendingReload() {
        if (reloadPending) {
            reloadPending = false;
            SwingUtilities.invokeLater(controller::reload);
        }
    }

    // --- bookmarks and notes ---------------------------------------------------------------------

    /// Adds or removes a bookmark on the selected row (`leftSide` picks the side; `null` prefers
    /// the candidate).
    public void toggleBookmark(Boolean leftSide) {
        DiffResult r = controller.result();
        int row = diffView.selectedDiffRow();
        if (r == null || row < 0) {
            return;
        }
        DiffRow d = r.rows().get(row);
        boolean left = leftSide != null ? leftSide && d.hasLeft() || !d.hasRight() : !d.hasRight();
        ProcessedLine line = left ? r.left(d) : r.right(d);
        if (line == null) {
            return;
        }
        Bookmark.Side side = left ? Bookmark.Side.LEFT : Bookmark.Side.RIGHT;
        List<Bookmark> next = new ArrayList<>(bookmarks);
        boolean removed = next.removeIf(b -> b.side() == side && b.line() == line.number());
        if (!removed) {
            next.add(new Bookmark(side, line.number(), line.text(), ""));
        }
        setBookmarks(next);
        statusMessage = (removed ? "Removed bookmark from " : "Bookmarked ") + (left ? "baseline" : "candidate")
                + " line " + line.number();
        host.tabChanged(this);
    }

    /// Edits the note of the bookmark on the selected row (adding one if needed).
    public void editSelectedNote() {
        DiffResult r = controller.result();
        int row = diffView.selectedDiffRow();
        if (r == null || row < 0) {
            return;
        }
        DiffRow d = r.rows().get(row);
        for (Bookmark b : bookmarks) {
            if (b.side() == Bookmark.Side.RIGHT && d.hasRight() && r.right(d).number() == b.line()
                    || b.side() == Bookmark.Side.LEFT && d.hasLeft() && r.left(d).number() == b.line()) {
                editNote(b);
                return;
            }
        }
        toggleBookmark(null);
        if (!bookmarks.isEmpty()) {
            for (Bookmark b : bookmarks) {
                ProcessedLine line = b.side() == Bookmark.Side.LEFT ? r.left(d) : r.right(d);
                if (line != null && line.number() == b.line()) {
                    editNote(b);
                    return;
                }
            }
        }
    }

    private void editNote(Bookmark b) {
        JTextArea area = new JTextArea(b.note(), 5, 40);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(area);
        String title = (b.side() == Bookmark.Side.LEFT ? "Baseline" : "Candidate") + " line " + b.line();
        SwingUtilities.invokeLater(area::requestFocusInWindow);
        if (JOptionPane.showConfirmDialog(this, scroll, "Note for " + title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        List<Bookmark> next = new ArrayList<>(bookmarks);
        next.replaceAll(x -> x.side() == b.side() && x.line() == b.line() ? x.withNote(area.getText().strip()) : x);
        setBookmarks(next);
    }

    /// Selects the next (`forward`) or previous bookmark.
    public void stepBookmark(boolean forward) {
        DiffResult r = controller.result();
        if (r == null || bookmarks.isEmpty()) {
            return;
        }
        int current = diffView.selectedDiffRow();
        int best = -1;
        for (Bookmark b : bookmarks) {
            int row = rowOf(r, b);
            if (row < 0) {
                continue;
            }
            if (forward && row > current && (best < 0 || row < best)
                    || !forward && row < current && row > best) {
                best = row;
            }
        }
        if (best < 0) { // wrap around
            for (Bookmark b : bookmarks) {
                int row = rowOf(r, b);
                if (row >= 0 && (best < 0 || forward && row < best || !forward && row > best)) {
                    best = row;
                }
            }
        }
        diffView.goToRow(best);
    }

    private void goToBookmark(Bookmark b) {
        DiffResult r = controller.result();
        if (r != null) {
            diffView.goToRow(rowOf(r, b));
        }
    }

    private static int rowOf(DiffResult r, Bookmark b) {
        return b.side() == Bookmark.Side.LEFT ? r.rowOfLeftLine(b.line()) : r.rowOfRightLine(b.line());
    }

    private void setBookmarks(List<Bookmark> list) {
        bookmarks = new ArrayList<>(list);
        bookmarks.sort(java.util.Comparator.comparing(Bookmark::side).thenComparingInt(Bookmark::line));
        bookmarkNotes.clear();
        for (Bookmark b : bookmarks) {
            bookmarkNotes.put(DiffCanvas.key(b.side() == Bookmark.Side.LEFT, b.line()), b.note());
        }
        notesPanel.setBookmarks(bookmarks);
        sidebar.setTitleAt(4, bookmarks.isEmpty() ? "Notes" : "Notes (" + bookmarks.size() + ")");
        diffView.canvas().repaint();
        markDirty();
    }

    /// Moves bookmarks whose lines shifted (the log was regenerated) back onto their text.
    private void relocateBookmarks(DiffResult r) {
        if (bookmarks.isEmpty()) {
            return;
        }
        List<Bookmark> moved = new ArrayList<>();
        boolean changed = false;
        for (Bookmark b : bookmarks) {
            List<String> lines = b.side() == Bookmark.Side.LEFT ? r.leftFile().lines() : r.rightFile().lines();
            int at = b.locate(lines);
            Bookmark nb = at > 0 && at != b.line() ? b.withLine(at) : b;
            changed |= nb != b;
            moved.add(nb);
        }
        if (changed) {
            boolean wasDirty = dirty;
            setBookmarks(moved);
            dirty = wasDirty;
        }
    }

    private void markDirty() {
        if (!dirty) {
            dirty = true;
            host.tabChanged(this);
        }
    }

    // --- sessions --------------------------------------------------------------------------------

    /// Restores a saved session into this tab.
    public void loadSession(Session s, Path file) {
        sessionFile = file;
        List<Source> extras = new ArrayList<>();
        for (String b : s.extraBaselines()) {
            extras.add(Source.parse(b));
        }
        controller.setOptions(s.options());
        controller.setExtraBaselines(extras);
        notes = s.notes();
        notesPanel.setNotes(notes);
        setBookmarks(s.bookmarks());
        open(Source.parse(s.left()), Source.parse(s.right()));
        dirty = false;
        host.tabChanged(this);
    }

    /// Saves sources, options, bookmarks and notes; asks for a file when needed.
    ///
    /// @return whether the session was saved
    public boolean saveSession(boolean saveAs) {
        if (controller.left() == null || controller.right() == null) {
            JOptionPane.showMessageDialog(this, "Open two logs before saving a session.", "Save session",
                    JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        Path target = sessionFile;
        if (target == null || saveAs) {
            JFileChooser c = host.chooser("Save session");
            c.resetChoosableFileFilters();
            c.setFileFilter(new FileNameExtensionFilter("LogDelta sessions (*." + SessionIO.EXTENSION + ")",
                    SessionIO.EXTENSION));
            c.setSelectedFile(new File(c.getCurrentDirectory(), baseName() + "." + SessionIO.EXTENSION));
            if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return false;
            }
            target = c.getSelectedFile().toPath();
            if (!target.toString().endsWith("." + SessionIO.EXTENSION)) {
                target = target.resolveSibling(target.getFileName() + "." + SessionIO.EXTENSION);
            }
        }
        try {
            Path dir = target.toAbsolutePath().getParent();
            String stem = target.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            String left = specFor(controller.left(), dir, stem + "-baseline.log");
            String right = specFor(controller.right(), dir, stem + "-candidate.log");
            List<String> extras = new ArrayList<>();
            int n = 2;
            for (Source s : controller.extraBaselines()) {
                extras.add(specFor(s, dir, stem + "-baseline-" + n++ + ".log"));
            }
            SessionIO.write(new Session(left, right, extras, controller.options(), bookmarks, notes), target);
            sessionFile = target;
            dirty = false;
            statusMessage = "Saved session to " + target;
            host.settings().setLastDirectory(dir);
            host.tabChanged(this);
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Cannot save the session: " + e.getMessage(), "Save session",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /// The source as stored in a session; pasted text is written next to the session first.
    private static String specFor(Source s, Path dir, String fileName) throws IOException {
        if (s instanceof Source.Text t) {
            Path file = dir.resolve(fileName);
            Files.write(file, t.log().lines(), StandardCharsets.UTF_8);
            return file.toString();
        }
        return s.spec();
    }

    private String baseName() {
        String l = controller.left() == null ? "baseline" : stripExtension(controller.left().displayName());
        String r = controller.right() == null ? "candidate" : stripExtension(controller.right().displayName());
        return (l + "-vs-" + r).replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }

    // --- editing and export ----------------------------------------------------------------------

    /// Opens the rule editor, optionally with a sample line.
    public void editRules(String sample) {
        RuleEditorDialog dialog = new RuleEditorDialog(SwingUtilities.getWindowAncestor(this),
                controller.options().rules(), sample, host.theme().palette());
        RuleSet edited = dialog.showDialog();
        if (edited != null) {
            controller.setOptions(controller.options().toBuilder().rules(edited).build());
        }
    }

    /// Suggests an ignore pattern for the selected line.
    public void ignoreSelected() {
        DiffResult r = controller.result();
        int row = diffView.selectedDiffRow();
        if (r == null || row < 0) {
            return;
        }
        DiffRow d = r.rows().get(row);
        ProcessedLine line = d.hasRight() ? r.right(d) : r.left(d);
        if (line == null) {
            return;
        }
        sidebar.setSelectedComponent(rulesPanel);
        rulesPanel.addIgnore(IgnorePatterns.suggest(line));
    }

    /// Copies the selected line: `side` is `left`, `right` or `both`.
    public void copy(String side) {
        String text = diffView.selectedText(side);
        if (text != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            statusMessage = "Copied to clipboard";
            host.tabChanged(this);
        }
    }

    /// Copies a Markdown summary of the comparison.
    public void copySummary() {
        DiffResult r = controller.result();
        if (r == null) {
            return;
        }
        String md = Reports.writer(ReportFormat.MARKDOWN).render(r, ReportOptions.DEFAULT.withMaxHunks(5));
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(md), null);
        statusMessage = "Markdown summary copied to clipboard";
        host.tabChanged(this);
    }

    /// Exports a report.
    public void export(ReportFormat format) {
        DiffResult r = controller.result();
        if (r == null) {
            return;
        }
        JFileChooser c = host.chooser("Export " + format.displayName());
        c.resetChoosableFileFilters();
        c.setFileFilter(new FileNameExtensionFilter(format.displayName(), format.extension()));
        String base = stripExtension(r.leftFile().displayName()) + "-vs-" + stripExtension(r.rightFile().displayName());
        c.setSelectedFile(new File(c.getCurrentDirectory(), base + "." + format.extension()));
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = c.getSelectedFile().toPath();
        if (!target.getFileName().toString().contains(".")) {
            target = target.resolveSibling(target.getFileName() + "." + format.extension());
        }
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this, target.getFileName() + " exists. Replace it?",
                "Export", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            ReportOptions options = new ReportOptions(Math.max(3, context), format == ReportFormat.HTML, 0);
            Reports.writeFile(r, format, options, target);
            host.settings().setLastDirectory(target.getParent());
            statusMessage = "Exported " + format.displayName() + " to " + target;
            host.tabChanged(this);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Export",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    static String stripExtension(String name) {
        String n = name.endsWith(".gz") ? name.substring(0, name.length() - 3) : name;
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /// Loads a profile into this comparison.
    public void loadProfile() {
        JFileChooser c = host.chooser("Load profile");
        c.resetChoosableFileFilters();
        c.setFileFilter(new FileNameExtensionFilter("LogDelta profiles (*." + ProfileIO.EXTENSION + ")",
                ProfileIO.EXTENSION));
        if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            controller.setOptions(ProfileIO.read(c.getSelectedFile().toPath()));
            statusMessage = "Loaded profile " + c.getSelectedFile().getName();
            host.tabChanged(this);
        } catch (IOException | ProfileParseException e) {
            JOptionPane.showMessageDialog(this, "Cannot load profile:\n" + e.getMessage(), "Load profile",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /// Saves this comparison's options as a profile.
    public void saveProfile() {
        JFileChooser c = host.chooser("Save profile");
        c.resetChoosableFileFilters();
        c.setFileFilter(new FileNameExtensionFilter("LogDelta profiles (*." + ProfileIO.EXTENSION + ")",
                ProfileIO.EXTENSION));
        c.setSelectedFile(new File(c.getCurrentDirectory(), "logdelta." + ProfileIO.EXTENSION));
        if (c.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = c.getSelectedFile().toPath();
        if (!target.toString().endsWith("." + ProfileIO.EXTENSION)) {
            target = target.resolveSibling(target.getFileName() + "." + ProfileIO.EXTENSION);
        }
        try {
            ProfileIO.write(controller.options(), target);
            statusMessage = "Saved profile to " + target;
            host.tabChanged(this);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Cannot save profile: " + e.getMessage(), "Save profile",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /// Headers for URL sources, e.g. an `Authorization` header for a CI server.
    public void setRemoteOptions(LogSources.RemoteOptions options) {
        controller.setRemoteOptions(options);
    }

    private void showContextMenu(boolean leftSide, int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Copy " + (leftSide ? "Baseline" : "Candidate") + " Line", () -> copy(leftSide ? "left" : "right")));
        menu.add(item("Copy Both Lines", () -> copy("both")));
        menu.addSeparator();
        menu.add(item("Toggle Bookmark", () -> toggleBookmark(leftSide)));
        menu.add(item("Bookmark Note…", this::editSelectedNote));
        menu.addSeparator();
        menu.add(item("Ignore Lines Like This…", this::ignoreSelected));
        menu.add(item("Test Line in Rule Editor…",
                () -> editRules(diffView.selectedText(leftSide ? "left" : "right"))));
        menu.add(item("Find This Text", () -> {
            String t = diffView.selectedText(leftSide ? "left" : "right");
            if (t != null) {
                diffView.openSearch(t.strip());
            }
        }));
        menu.show(diffView.canvas(), x, y);
    }

    private static JMenuItem item(String label, Runnable action) {
        JMenuItem it = new JMenuItem(label);
        it.addActionListener(_ -> action.run());
        return it;
    }

    // --- view settings ---------------------------------------------------------------------------

    /// Applies the theme and font.
    public void applyAppearance(Theme theme, float fontSize) {
        DiffPalette p = theme.palette();
        diffView.setPalette(p);
        diffView.setEditorFont(Theme.monoFont(fontSize));
        welcome.setPalette(p);
        summary.setPalette(p);
        insights.setPalette(p);
        timing.setPalette(p);
        notesPanel.setPalette(p);
        if (controller.result() != null) {
            summary.show(controller.result());
        }
    }

    /// Applies the persisted view toggles.
    public void applyViewSettings() {
        var s = host.settings();
        collapse = s.collapseUnchanged();
        context = s.contextLines();
        diffView.refold(collapse, context);
        diffView.setShowNormalized(s.showNormalized());
        diffView.setWrap(s.wrapLines());
        diffView.setSyntaxColoring(s.syntaxColoring());
        diffView.setShowElapsedTime(s.showElapsedTime());
        updateWatching();
    }

    /// Shows the recent comparisons on the empty tab.
    public void setRecent(List<io.github.logdelta.gui.settings.AppSettings.RecentPair> recent) {
        welcome.setRecent(recent);
    }

    /// Selects a sidebar tab by title prefix (e.g. `"Notes"`).
    public void showSidebar(String title) {
        for (int i = 0; i < sidebar.getTabCount(); i++) {
            if (sidebar.getTitleAt(i).startsWith(title)) {
                sidebar.setSelectedIndex(i);
            }
        }
    }

    private static String esc(String s) {
        return io.github.logdelta.core.util.Text.escapeHtml(s);
    }
}
