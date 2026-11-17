// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui;

import io.github.logdelta.core.Version;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.engine.DiffResult;
import io.github.logdelta.core.io.LogSources;
import io.github.logdelta.core.report.ReportFormat;
import io.github.logdelta.core.session.Session;
import io.github.logdelta.core.session.SessionIO;
import io.github.logdelta.core.session.SessionParseException;
import io.github.logdelta.gui.dialog.AboutDialog;
import io.github.logdelta.gui.dialog.OpenUrlDialog;
import io.github.logdelta.gui.model.Source;
import io.github.logdelta.gui.model.ViewFilter;
import io.github.logdelta.gui.settings.AppSettings;
import io.github.logdelta.gui.settings.AppSettings.RecentPair;
import io.github.logdelta.gui.tab.AppTab;
import io.github.logdelta.gui.tab.ComparisonTab;
import io.github.logdelta.gui.tab.FolderTab;
import io.github.logdelta.gui.tab.TabHost;
import io.github.logdelta.gui.theme.Theme;
import io.github.logdelta.gui.tour.Tour;
import io.github.logdelta.gui.util.AppIcon;
import io.github.logdelta.gui.util.Icons;
import io.github.logdelta.gui.view.DiffView;
import io.github.logdelta.gui.view.FileBar;
import io.github.logdelta.gui.view.StatusBar;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// The main window: menus, toolbar, a strip of tabs (comparisons of logs or folders) and the
/// status bar. Commands act on the selected tab.
public final class MainFrame extends JFrame implements TabHost {

    private static final int MENU = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    private final AppSettings settings;
    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final StatusBar statusBar;
    private final JMenu recentMenu = new JMenu("Open Recent");
    private final List<AppTab> open = new ArrayList<>();

    private Theme theme;
    private float fontSize;

    // Actions that need a comparison result.
    private final Action nextChange = action("Next Change", Icons.next(), key(KeyEvent.VK_F7, 0),
            "Next change (F7)", () -> view(DiffView::nextChange));
    private final Action previousChange = action("Previous Change", Icons.previous(),
            key(KeyEvent.VK_F7, InputEvent.SHIFT_DOWN_MASK), "Previous change (Shift+F7)",
            () -> view(DiffView::previousChange));
    private final Action nextProblem = action("Next New Problem", null, key(KeyEvent.VK_F8, 0), null,
            () -> view(v -> v.stepProblem(true)));
    private final Action previousProblem = action("Previous New Problem", null,
            key(KeyEvent.VK_F8, InputEvent.SHIFT_DOWN_MASK), null, () -> view(v -> v.stepProblem(false)));
    private final Action nextBookmark = action("Next Bookmark", null, key(KeyEvent.VK_F2, 0), null,
            () -> cmp(t -> t.stepBookmark(true)));
    private final Action previousBookmark = action("Previous Bookmark", null,
            key(KeyEvent.VK_F2, InputEvent.SHIFT_DOWN_MASK), null, () -> cmp(t -> t.stepBookmark(false)));
    private final Action toggleBookmark = action("Toggle Bookmark", null, key(KeyEvent.VK_B, MENU), null,
            () -> cmp(t -> t.toggleBookmark(null)));
    private final Action bookmarkNote = action("Bookmark Note…", null,
            key(KeyEvent.VK_B, MENU | InputEvent.SHIFT_DOWN_MASK), null, () -> cmp(ComparisonTab::editSelectedNote));
    private final Action find = action("Find…", Icons.search(), key(KeyEvent.VK_F, MENU),
            "Find in both logs (Ctrl+F)", () -> view(v -> v.openSearch(null)));
    private final Action filter = action("Filter Lines…", null, key(KeyEvent.VK_L, MENU), null,
            () -> view(DiffView::focusFilter));
    private final Action expandAll = action("Expand All", null, key(KeyEvent.VK_E, MENU), null,
            () -> view(DiffView::expandAll));
    private final Action reload = action("Compare Again", Icons.reload(), key(KeyEvent.VK_F5, 0),
            "Compare again (F5)", () -> cmp(t -> t.controller().reload()));
    private final Action swap = action("Swap Sides", Icons.swap(), key(KeyEvent.VK_S, MENU | InputEvent.SHIFT_DOWN_MASK),
            "Swap baseline and candidate", () -> cmp(t -> t.controller().swap()));
    private final Action exportHtml = action("HTML Report…", Icons.export(), key(KeyEvent.VK_E,
            MENU | InputEvent.SHIFT_DOWN_MASK), "Export an HTML report", () -> cmp(t -> t.export(ReportFormat.HTML)));
    private final Action copySummary = action("Copy Summary as Markdown", null,
            key(KeyEvent.VK_C, MENU | InputEvent.SHIFT_DOWN_MASK), null, this::copySummary);
    private final Action ignoreLike = action("Ignore Lines Like This…", null, key(KeyEvent.VK_I, MENU), null,
            () -> cmp(ComparisonTab::ignoreSelected));
    private final Action copyLine = action("Copy Line", null, key(KeyEvent.VK_C, MENU), null,
            () -> cmp(t -> t.copy("right")));
    private final Action saveSession = action("Save Session", null, key(KeyEvent.VK_S, MENU), null,
            () -> cmp(t -> t.saveSession(false)));
    private final Action liveTail = action("Live Tail", Icons.live(), key(KeyEvent.VK_L, MENU | InputEvent.SHIFT_DOWN_MASK),
            "Live tail: follow the logs as they grow (Ctrl+Shift+L)", this::toggleLiveTail);

    /// Creates the window.
    ///
    /// @param settings persisted settings
    public MainFrame(AppSettings settings) {
        super(Version.NAME);
        this.settings = settings;
        this.theme = settings.theme();
        this.fontSize = settings.fontSize();
        setIconImages(AppIcon.images());

        statusBar = new StatusBar(() -> current().ifPresent(AppTab::cancel));
        tabs.addChangeListener(_ -> tabSelected());
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = tabs.indexAtLocation(e.getX(), e.getY());
                if (i >= 0 && SwingUtilities.isMiddleMouseButton(e)) {
                    closeTab(open.get(i));
                }
            }
        });

        JPanel root = new JPanel(new BorderLayout());
        root.add(buildToolbar(), BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        root.add(statusBar, BorderLayout.SOUTH);
        setContentPane(root);
        setJMenuBar(buildMenus());
        root.setTransferHandler(new FileBar.FileDropHandler(this::dropped, null));

        newComparisonTab();
        updateActions();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });
        restoreBounds();
    }

    /// Shows the first-run tour if it has not been shown yet.
    public void maybeShowTour() {
        if (!settings.tourShown()) {
            SwingUtilities.invokeLater(() -> Tour.show(this, settings, this::openExampleForTour));
        }
    }

    private void openExampleForTour() {
        ComparisonTab t = reusableComparisonTab();
        t.openExample();
    }

    // --- TabHost -------------------------------------------------------------------------------

    @Override
    public AppSettings settings() {
        return settings;
    }

    @Override
    public Theme theme() {
        return theme;
    }

    @Override
    public void tabChanged(AppTab tab) {
        int i = open.indexOf(tab);
        if (i < 0) {
            return;
        }
        setTabTitle(i, tab);
        if (tabs.getSelectedIndex() == i) {
            showStatus(tab);
            updateActions();
            updateTitle();
        }
    }

    @Override
    public void remember(Source left, Source right) {
        if (left == null || right == null || left.spec() == null || right.spec() == null) {
            return;
        }
        settings.addRecent(new RecentPair(absolute(left), absolute(right)));
        rebuildRecentMenu();
        for (AppTab t : open) {
            if (t instanceof ComparisonTab c) {
                c.setRecent(settings.recent());
            }
        }
    }

    @Override
    public JFileChooser chooser(String title) {
        Path dir = settings.lastDirectory();
        JFileChooser c = new JFileChooser(dir != null && Files.isDirectory(dir) ? dir.toFile() : null);
        c.setDialogTitle(title);
        c.addChoosableFileFilter(new FileNameExtensionFilter("Log files (*.log, *.txt, *.out, *.gz)",
                "log", "txt", "out", "gz", "trace"));
        return c;
    }

    @Override
    public void openInNewTab(Path left, Path right, DiffOptions options) {
        ComparisonTab t = addComparisonTab(options);
        t.open(Source.of(left), Source.of(right));
    }

    private static String absolute(Source s) {
        return s.path() != null ? s.path().toAbsolutePath().normalize().toString() : s.spec();
    }

    // --- tabs ------------------------------------------------------------------------------------

    private Map<String, Runnable> welcomeLinks() {
        Map<String, Runnable> links = new LinkedHashMap<>();
        links.put("Compare folders…", this::compareFolders);
        links.put("Open URLs…", this::openUrls);
        links.put("Paste a log", () -> cmp(t -> t.paste(t.controller().left() == null)));
        links.put("Open a session…", this::openSession);
        return links;
    }

    private ComparisonTab newComparisonTab() {
        return addComparisonTab(currentOptions());
    }

    private DiffOptions currentOptions() {
        return current().filter(t -> t instanceof ComparisonTab).map(t -> ((ComparisonTab) t).controller().options())
                .orElseGet(settings::sessionOptions);
    }

    private ComparisonTab addComparisonTab(DiffOptions options) {
        ComparisonTab t = new ComparisonTab(this, options, welcomeLinks());
        t.applyAppearance(theme, fontSize);
        t.applyViewSettings();
        t.setTransferHandler(new FileBar.FileDropHandler(this::dropped, null));
        t.diffView().canvas().setTransferHandler(t.getTransferHandler());
        t.diffView().setFilterListener(f -> {
            if (current().orElse(null) == t) {
                syncFilterMenu(f.mode());
            }
        });
        addTab(t);
        return t;
    }

    private FolderTab addFolderTab() {
        FolderTab t = new FolderTab(this, currentOptions());
        addTab(t);
        return t;
    }

    private void addTab(AppTab t) {
        open.add(t);
        tabs.addTab(t.title(), t.component());
        int i = open.size() - 1;
        tabs.setTabComponentAt(i, new TabLabel(t));
        setTabTitle(i, t);
        tabs.setSelectedIndex(i);
    }

    /// The selected comparison tab if it is empty, otherwise a new one.
    private ComparisonTab reusableComparisonTab() {
        if (current().orElse(null) instanceof ComparisonTab c && c.isEmpty()) {
            return c;
        }
        return newComparisonTab();
    }

    private void closeTab(AppTab t) {
        if (!t.confirmClose()) {
            return;
        }
        t.dispose();
        int i = open.indexOf(t);
        open.remove(i);
        tabs.removeTabAt(i);
        if (open.isEmpty()) {
            newComparisonTab();
        }
    }

    private void setTabTitle(int i, AppTab t) {
        tabs.setTitleAt(i, t.title());
        tabs.setToolTipTextAt(i, t.tooltip());
        if (tabs.getTabComponentAt(i) instanceof TabLabel label) {
            label.refresh();
        }
    }

    private java.util.Optional<AppTab> current() {
        int i = tabs.getSelectedIndex();
        return i < 0 || i >= open.size() ? java.util.Optional.empty() : java.util.Optional.of(open.get(i));
    }

    private void cmp(Consumer<ComparisonTab> action) {
        if (current().orElse(null) instanceof ComparisonTab c) {
            action.accept(c);
        }
    }

    private void view(Consumer<DiffView> action) {
        cmp(t -> action.accept(t.diffView()));
    }

    private void tabSelected() {
        current().ifPresent(t -> {
            showStatus(t);
            if (t instanceof ComparisonTab c) {
                syncToggles("live", c.isLiveTail());
                syncToggles("multiline", c.controller().options().multilineEntries());
                syncFilterMenu(c.diffView().filter().mode());
            }
        });
        updateActions();
        updateTitle();
    }

    private void showStatus(AppTab t) {
        statusBar.setBusy(t.isBusy(), t.busyStage(), t.busyFraction());
        if (!t.isBusy()) {
            statusBar.setMessage(t.statusMessage());
        }
        statusBar.setDetails(t.statusDetails());
    }

    /// Tab header: the title and a close button.
    private final class TabLabel extends JPanel {
        private final AppTab tab;
        private final JLabel label = new JLabel();

        TabLabel(AppTab tab) {
            this.tab = tab;
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            JButton close = new JButton("✕");
            close.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
            close.setContentAreaFilled(false);
            close.setFocusable(false);
            close.setToolTipText("Close tab (" + (MENU == InputEvent.META_DOWN_MASK ? "⌘" : "Ctrl+") + "W)");
            close.addActionListener(_ -> closeTab(tab));
            add(label);
            add(close);
            refresh();
        }

        void refresh() {
            label.setText(tab.title());
            label.setIcon(tab instanceof FolderTab ? Icons.folder() : null);
            label.setToolTipText(tab.tooltip());
        }
    }

    // --- layout ----------------------------------------------------------------------------------

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setName("tour.toolbar");
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 0, 6));
        bar.add(toolButton(action("Open…", Icons.open(), null, "Open baseline and candidate (Ctrl+O)",
                () -> cmp(ComparisonTab::openBoth))));
        bar.add(toolButton(action("New Tab", Icons.newTab(), null, "New comparison tab (Ctrl+T)",
                this::newComparisonTab)));
        bar.add(toolButton(swap));
        bar.add(toolButton(reload));
        bar.addSeparator();
        bar.add(toolButton(previousChange));
        bar.add(toolButton(nextChange));
        bar.addSeparator();
        bar.add(toggle(Icons.collapse(), "Collapse unchanged lines (Ctrl+U)", "collapse",
                settings.collapseUnchanged(), this::setCollapse));
        bar.add(toggle(Icons.normalized(), "Show normalized text (Ctrl+M)", "normalized",
                settings.showNormalized(), this::setShowNormalized));
        bar.add(toggle(Icons.wrap(), "Wrap long lines (Alt+Z)", "wrap", settings.wrapLines(), this::setWrap));
        bar.add(toggle(Icons.clock(), "Show elapsed time next to line numbers (Alt+T)", "elapsed",
                settings.showElapsedTime(), this::setElapsed));
        JToggleButton live = new JToggleButton(liveTail);
        live.setHideActionText(true);
        live.setFocusable(false);
        live.putClientProperty("logdelta.sync", "live");
        bar.add(live);
        bar.addSeparator();
        bar.add(toolButton(find));
        bar.add(toolButton(action("Rules…", Icons.rules(), null, "Edit normalization rules (Ctrl+R)",
                () -> cmp(t -> t.editRules(null)))));
        bar.add(toolButton(exportHtml));
        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    private static JToggleButton toggle(Icon icon, String tip, String sync, boolean selected,
                                        Consumer<Boolean> onChange) {
        JToggleButton b = new JToggleButton(icon);
        b.setToolTipText(tip);
        b.setSelected(selected);
        b.setFocusable(false);
        b.putClientProperty("logdelta.sync", sync);
        b.addActionListener(_ -> onChange.accept(b.isSelected()));
        return b;
    }

    private static JButton toolButton(Action a) {
        JButton b = new JButton(a);
        b.setHideActionText(true);
        b.setFocusable(false);
        return b;
    }

    private JMenuBar buildMenus() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.setMnemonic('F');
        file.add(item(action("New Comparison Tab", null, key(KeyEvent.VK_T, MENU), null, this::newComparisonTab)));
        file.add(item(action("Open Logs…", null, key(KeyEvent.VK_O, MENU), null, () -> {
            ComparisonTab t = current().orElse(null) instanceof ComparisonTab c ? c : newComparisonTab();
            t.openBoth();
        })));
        file.add(item(action("Open Baseline…", null, null, null, () -> cmp(t -> t.browse(true)))));
        file.add(item(action("Open Candidate…", null, null, null, () -> cmp(t -> t.browse(false)))));
        file.add(item(action("Open URLs…", null, key(KeyEvent.VK_O, MENU | InputEvent.SHIFT_DOWN_MASK), null,
                this::openUrls)));
        file.add(item(action("Paste as Candidate", null, key(KeyEvent.VK_V, MENU | InputEvent.SHIFT_DOWN_MASK),
                null, () -> cmp(t -> t.paste(false)))));
        file.add(item(action("Paste as Baseline", null, null, null, () -> cmp(t -> t.paste(true)))));
        file.add(item(action("Compare Folders…", null, key(KeyEvent.VK_D, MENU | InputEvent.SHIFT_DOWN_MASK),
                null, this::compareFolders)));
        file.add(recentMenu);
        file.add(item(action("Open Example", null, null, null, () -> reusableComparisonTab().openExample())));
        file.addSeparator();
        file.add(item(action("Open Session…", null, null, null, this::openSession)));
        file.add(item(saveSession));
        file.add(item(action("Save Session As…", null, null, null, () -> cmp(t -> t.saveSession(true)))));
        file.addSeparator();
        file.add(item(action("Load Profile…", null, null, null, () -> cmp(ComparisonTab::loadProfile))));
        file.add(item(action("Save Profile As…", null, null, null, () -> cmp(ComparisonTab::saveProfile))));
        file.addSeparator();
        JMenu export = new JMenu("Export");
        export.add(item(exportHtml));
        export.add(item(action("Markdown Summary…", null, null, null, () -> cmp(t -> t.export(ReportFormat.MARKDOWN)))));
        export.add(item(action("JSON…", null, null, null, () -> cmp(t -> t.export(ReportFormat.JSON)))));
        export.add(item(action("JUnit XML…", null, null, null, () -> cmp(t -> t.export(ReportFormat.JUNIT)))));
        export.add(item(action("Unified Diff…", null, null, null, () -> cmp(t -> t.export(ReportFormat.UNIFIED)))));
        file.add(export);
        file.add(item(copySummary));
        file.addSeparator();
        file.add(item(action("Close Tab", null, key(KeyEvent.VK_W, MENU), null,
                () -> current().ifPresent(this::closeTab))));
        file.add(item(action("Exit", null, key(KeyEvent.VK_Q, MENU), null, this::exit)));
        rebuildRecentMenu();

        JMenu edit = new JMenu("Edit");
        edit.setMnemonic('E');
        edit.add(item(copyLine));
        edit.add(item(action("Copy Baseline Line", null, null, null, () -> cmp(t -> t.copy("left")))));
        edit.add(item(action("Copy Both Lines", null, null, null, () -> cmp(t -> t.copy("both")))));
        edit.addSeparator();
        edit.add(item(find));
        edit.add(item(filter));
        edit.addSeparator();
        edit.add(item(toggleBookmark));
        edit.add(item(bookmarkNote));
        edit.addSeparator();
        edit.add(item(ignoreLike));
        edit.add(item(action("Edit Rules…", null, key(KeyEvent.VK_R, MENU), null, () -> cmp(t -> t.editRules(null)))));

        JMenu view = new JMenu("View");
        view.setMnemonic('V');
        view.add(check("Collapse Unchanged Lines", key(KeyEvent.VK_U, MENU), "collapse", settings.collapseUnchanged(),
                this::setCollapse));
        JMenu contextMenu = new JMenu("Context Lines");
        ButtonGroup contextGroup = new ButtonGroup();
        for (int n : new int[] {0, 1, 3, 5, 10, 25}) {
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(String.valueOf(n), n == settings.contextLines());
            it.addActionListener(_ -> setContext(n));
            contextGroup.add(it);
            contextMenu.add(it);
        }
        view.add(contextMenu);
        view.add(item(expandAll));
        JMenu panes = new JMenu("Pane Widths");
        panes.add(item(action("Widen Baseline", null,
                key(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), null,
                () -> view(v -> v.canvas().setSplit(v.canvas().split() + DiffView.SPLIT_STEP)))));
        panes.add(item(action("Widen Candidate", null,
                key(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), null,
                () -> view(v -> v.canvas().setSplit(v.canvas().split() - DiffView.SPLIT_STEP)))));
        panes.add(item(action("Equal Widths", null, null, null, () -> view(v -> v.canvas().setSplit(0.5)))));
        view.add(panes);
        view.addSeparator();
        view.add(check("Show Normalized Text", key(KeyEvent.VK_M, MENU), "normalized", settings.showNormalized(),
                this::setShowNormalized));
        view.add(check("Wrap Long Lines", key(KeyEvent.VK_Z, InputEvent.ALT_DOWN_MASK), "wrap", settings.wrapLines(),
                this::setWrap));
        view.add(check("Syntax Colouring", null, "syntax", settings.syntaxColoring(), this::setSyntax));
        view.add(check("Show Elapsed Time", key(KeyEvent.VK_T, InputEvent.ALT_DOWN_MASK), "elapsed",
                settings.showElapsedTime(), this::setElapsed));
        view.addSeparator();
        JMenu show = new JMenu("Show");
        ButtonGroup showGroup = new ButtonGroup();
        int digit = KeyEvent.VK_1;
        for (ViewFilter.Mode m : ViewFilter.Mode.values()) {
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(m.label(), m == ViewFilter.Mode.ALL);
            it.setAccelerator(key(digit++, InputEvent.ALT_DOWN_MASK));
            it.putClientProperty("logdelta.filter", m);
            it.addActionListener(_ -> view(v -> v.setFilter(v.filter().withMode(m))));
            showGroup.add(it);
            show.add(it);
        }
        view.add(show);
        view.addSeparator();
        JMenu themeMenu = new JMenu("Theme");
        ButtonGroup themeGroup = new ButtonGroup();
        for (Theme t : Theme.values()) {
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(t.displayName(), t == theme);
            it.addActionListener(_ -> setTheme(t));
            themeGroup.add(it);
            themeMenu.add(it);
        }
        view.add(themeMenu);
        view.add(item(action("Zoom In", null, key(KeyEvent.VK_EQUALS, MENU), null, () -> zoom(1))));
        view.add(item(action("Zoom Out", null, key(KeyEvent.VK_MINUS, MENU), null, () -> zoom(-1))));
        view.add(item(action("Actual Size", null, key(KeyEvent.VK_0, MENU), null, () -> zoom(0))));

        JMenu compare = new JMenu("Compare");
        compare.setMnemonic('C');
        compare.add(item(reload));
        compare.add(item(swap));
        compare.add(item(action("Add Baseline Run…", null, null, null, () -> cmp(ComparisonTab::addBaseline))));
        compare.addSeparator();
        compare.add(check("Keep Stack Traces with Their Line", null, "multiline", false, on -> cmp(t ->
                t.controller().setOptions(t.controller().options().toBuilder().multilineEntries(on).build()))));
        compare.addSeparator();
        JCheckBoxMenuItem liveItem = new JCheckBoxMenuItem(liveTail);
        liveItem.putClientProperty("logdelta.sync", "live");
        liveItem.setIcon(null);
        compare.add(liveItem);
        compare.add(check("Compare Again When Files Change", null, "follow", settings.followChanges(), on -> {
            settings.setFollowChanges(on);
            open.forEach(t -> {
                if (t instanceof ComparisonTab c) {
                    c.updateWatching();
                }
            });
        }));

        JMenu nav = new JMenu("Navigate");
        nav.setMnemonic('N');
        nav.add(item(nextChange));
        nav.add(item(previousChange));
        nav.add(item(nextProblem));
        nav.add(item(previousProblem));
        nav.add(item(nextBookmark));
        nav.add(item(previousBookmark));
        nav.add(item(action("First Difference", null, key(KeyEvent.VK_HOME, InputEvent.ALT_DOWN_MASK), null,
                () -> cmp(t -> {
                    DiffResult r = t.result();
                    if (r != null) {
                        t.diffView().goToRow(r.insights().firstDivergenceRow());
                    }
                }))));
        nav.addSeparator();
        nav.add(item(action("Next Tab", null, key(KeyEvent.VK_PAGE_DOWN, MENU), null,
                () -> tabs.setSelectedIndex((tabs.getSelectedIndex() + 1) % Math.max(1, tabs.getTabCount())))));
        nav.add(item(action("Previous Tab", null, key(KeyEvent.VK_PAGE_UP, MENU), null,
                () -> tabs.setSelectedIndex(Math.floorMod(tabs.getSelectedIndex() - 1, Math.max(1, tabs.getTabCount()))))));

        JMenu help = new JMenu("Help");
        help.setMnemonic('H');
        help.add(item(action("Take the Tour", null, null, null,
                () -> Tour.show(this, settings, this::openExampleForTour))));
        help.add(item(action("Keyboard Shortcuts", null, key(KeyEvent.VK_F1, 0), null,
                () -> AboutDialog.showShortcuts(this))));
        help.add(item(action("About " + Version.NAME, null, null, null, () -> AboutDialog.showAbout(this))));

        bar.add(file);
        bar.add(edit);
        bar.add(view);
        bar.add(compare);
        bar.add(nav);
        bar.add(help);
        return bar;
    }

    private static JMenuItem item(Action a) {
        JMenuItem it = new JMenuItem(a);
        it.setIcon(null);
        return it;
    }

    private static JCheckBoxMenuItem check(String label, KeyStroke key, String sync, boolean selected,
                                           Consumer<Boolean> onChange) {
        JCheckBoxMenuItem it = new JCheckBoxMenuItem(label, selected);
        if (key != null) {
            it.setAccelerator(key);
        }
        it.putClientProperty("logdelta.sync", sync);
        it.addActionListener(_ -> onChange.accept(it.isSelected()));
        return it;
    }

    private void rebuildRecentMenu() {
        recentMenu.removeAll();
        List<RecentPair> recent = settings.recent();
        for (RecentPair p : recent) {
            JMenuItem it = new JMenuItem(p.label());
            it.setToolTipText("<html>" + io.github.logdelta.core.util.Text.escapeHtml(p.left()) + "<br>"
                    + io.github.logdelta.core.util.Text.escapeHtml(p.right()) + "</html>");
            it.addActionListener(_ -> reusableComparisonTab().open(p.leftSource(), p.rightSource()));
            recentMenu.add(it);
        }
        if (!recent.isEmpty()) {
            recentMenu.addSeparator();
            JMenuItem clear = new JMenuItem("Clear List");
            clear.addActionListener(_ -> {
                settings.clearRecent();
                rebuildRecentMenu();
                open.forEach(t -> {
                    if (t instanceof ComparisonTab c) {
                        c.setRecent(List.of());
                    }
                });
            });
            recentMenu.add(clear);
        }
        recentMenu.setEnabled(!recent.isEmpty());
    }

    // --- commands --------------------------------------------------------------------------------

    /// Opens sources given on the command line: a session, two folders, or files and URLs (more
    /// than two: all but the last are baselines).
    public void openArguments(List<String> args) {
        try {
            openArgumentsUnchecked(args);
        } catch (IllegalArgumentException e) { // malformed URL or path
            JOptionPane.showMessageDialog(this, "Cannot open " + String.join(" ", args) + ":\n" + e.getMessage(),
                    "Open", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openArgumentsUnchecked(List<String> args) {
        if (args.isEmpty()) {
            return;
        }
        if (args.size() == 1 && args.getFirst().endsWith("." + SessionIO.EXTENSION)) {
            openSession(Path.of(args.getFirst()));
            return;
        }
        if (args.size() == 2 && !LogSources.isUrl(args.get(0)) && Files.isDirectory(Path.of(args.get(0)))
                && Files.isDirectory(Path.of(args.get(1)))) {
            reusableFolderTab().open(Path.of(args.get(0)), Path.of(args.get(1)));
            return;
        }
        if (args.size() < 2) {
            reusableComparisonTab().controller().setLeft(Source.parse(args.getFirst()));
            return;
        }
        ComparisonTab t = reusableComparisonTab();
        List<Source> extras = new ArrayList<>();
        for (String a : args.subList(1, args.size() - 1)) {
            extras.add(Source.parse(a));
        }
        if (!extras.isEmpty()) {
            t.controller().setExtraBaselines(extras);
        }
        t.open(Source.parse(args.getFirst()), Source.parse(args.getLast()));
    }

    private FolderTab reusableFolderTab() {
        if (current().orElse(null) instanceof ComparisonTab c && c.isEmpty()) {
            closeTabQuietly(c);
        }
        return addFolderTab();
    }

    private void closeTabQuietly(AppTab t) {
        t.dispose();
        int i = open.indexOf(t);
        open.remove(i);
        tabs.removeTabAt(i);
    }

    private void dropped(List<Path> files) {
        List<Path> sessions = files.stream().filter(p -> p.toString().endsWith("." + SessionIO.EXTENSION)).toList();
        if (!sessions.isEmpty()) {
            sessions.forEach(this::openSession);
            return;
        }
        if (files.size() == 2 && Files.isDirectory(files.get(0)) && Files.isDirectory(files.get(1))) {
            reusableFolderTab().open(files.get(0), files.get(1));
            return;
        }
        List<Path> regular = files.stream().filter(Files::isRegularFile).toList();
        if (regular.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Drop two log files, two folders or a session file.", "Open",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ComparisonTab t = current().orElse(null) instanceof ComparisonTab c && (c.isEmpty() || regular.size() == 1)
                ? c : newComparisonTab();
        t.dropped(regular);
    }

    private void compareFolders() {
        JFileChooser c = chooser("Choose the baseline folder");
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        c.resetChoosableFileFilters();
        if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path left = c.getSelectedFile().toPath();
        c.setDialogTitle("Choose the candidate folder");
        c.setSelectedFile(null);
        if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            reusableFolderTab();
            return;
        }
        settings.setLastDirectory(c.getCurrentDirectory().toPath());
        reusableFolderTab().open(left, c.getSelectedFile().toPath());
    }

    private void openUrls() {
        OpenUrlDialog.Result r = OpenUrlDialog.show(this);
        if (r == null) {
            return;
        }
        ComparisonTab t = reusableComparisonTab();
        t.setRemoteOptions(r.remoteOptions());
        if (r.left() != null && r.right() != null) {
            t.open(r.left(), r.right());
        } else if (r.left() != null) {
            t.controller().setLeft(r.left());
        } else if (r.right() != null) {
            t.controller().setRight(r.right());
        }
    }

    private void openSession() {
        JFileChooser c = chooser("Open session");
        c.resetChoosableFileFilters();
        c.setFileFilter(new FileNameExtensionFilter("LogDelta sessions (*." + SessionIO.EXTENSION + ")",
                SessionIO.EXTENSION));
        if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            settings.setLastDirectory(c.getCurrentDirectory().toPath());
            openSession(c.getSelectedFile().toPath());
        }
    }

    private void openSession(Path file) {
        try {
            Session s = SessionIO.read(file);
            reusableComparisonTab().loadSession(s, file);
        } catch (IOException | SessionParseException | IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, "Cannot open the session:\n" + e.getMessage(), "Open session",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void copySummary() {
        switch (current().orElse(null)) {
            case ComparisonTab c -> c.copySummary();
            case FolderTab f -> f.copySummary();
            case null -> {
                // nothing open
            }
        }
    }

    private void toggleLiveTail() {
        cmp(t -> {
            if (!t.controller().hasFiles()) {
                syncToggles("live", false);
                JOptionPane.showMessageDialog(this, "Open a baseline and a candidate first.", "Live tail",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            t.setLiveTail(!t.isLiveTail());
            syncToggles("live", t.isLiveTail());
        });
    }

    // --- view settings ---------------------------------------------------------------------------

    private void setCollapse(boolean value) {
        settings.setCollapseUnchanged(value);
        syncToggles("collapse", value);
        applyViewSettings();
    }

    private void setContext(int lines) {
        settings.setContextLines(lines);
        applyViewSettings();
    }

    private void setShowNormalized(boolean value) {
        settings.setShowNormalized(value);
        syncToggles("normalized", value);
        applyViewSettings();
    }

    private void setWrap(boolean value) {
        settings.setWrapLines(value);
        syncToggles("wrap", value);
        applyViewSettings();
    }

    private void setSyntax(boolean value) {
        settings.setSyntaxColoring(value);
        syncToggles("syntax", value);
        applyViewSettings();
    }

    private void setElapsed(boolean value) {
        settings.setShowElapsedTime(value);
        syncToggles("elapsed", value);
        applyViewSettings();
        if (value && current().orElse(null) instanceof ComparisonTab c && c.result() != null
                && !c.result().timing().available()) {
            statusBar.setMessage("These logs have no timestamps LogDelta recognises");
        }
    }

    private void applyViewSettings() {
        open.forEach(t -> {
            if (t instanceof ComparisonTab c) {
                c.applyViewSettings();
            }
        });
    }

    private void setTheme(Theme t) {
        theme = t;
        settings.setTheme(t);
        t.install();
        applyAppearance();
    }

    private void zoom(int direction) {
        fontSize = direction == 0 ? 13f : Math.clamp(fontSize + direction, 9f, 28f);
        settings.setFontSize(fontSize);
        applyAppearance();
    }

    private void applyAppearance() {
        open.forEach(t -> {
            if (t instanceof ComparisonTab c) {
                c.applyAppearance(theme, fontSize);
            }
        });
        repaint();
    }

    /// Keeps toolbar toggles and menu check boxes for the same setting in sync.
    private void syncToggles(String key, boolean value) {
        syncIn(getContentPane(), key, value);
        syncIn(getJMenuBar(), key, value);
    }

    private static void syncIn(Container c, String key, boolean value) {
        if (c == null) {
            return;
        }
        Iterable<Component> children = c instanceof JMenu m ? List.of(m.getMenuComponents())
                : List.of(c.getComponents());
        for (Component child : children) {
            if (child instanceof AbstractButton b && key.equals(b.getClientProperty("logdelta.sync"))) {
                b.setSelected(value);
            }
            if (child instanceof Container cc && !(child instanceof ComparisonTab) && !(child instanceof FolderTab)) {
                syncIn(cc, key, value);
            }
        }
    }

    private void syncFilterMenu(ViewFilter.Mode mode) {
        JMenuBar bar = getJMenuBar();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            syncFilter(bar.getMenu(i), mode);
        }
    }

    private static void syncFilter(JMenu menu, ViewFilter.Mode mode) {
        for (Component c : menu.getMenuComponents()) {
            if (c instanceof JRadioButtonMenuItem it && it.getClientProperty("logdelta.filter") != null) {
                it.setSelected(it.getClientProperty("logdelta.filter") == mode);
            } else if (c instanceof JMenu sub) {
                syncFilter(sub, mode);
            }
        }
    }

    private void updateActions() {
        AppTab t = current().orElse(null);
        boolean cmpTab = t instanceof ComparisonTab;
        boolean has = t instanceof ComparisonTab c && c.result() != null;
        for (Action a : List.of(nextChange, previousChange, nextProblem, previousProblem, find, filter, expandAll,
                reload, exportHtml, ignoreLike, copyLine, toggleBookmark, bookmarkNote, nextBookmark,
                previousBookmark)) {
            a.setEnabled(has);
        }
        copySummary.setEnabled(has || t instanceof FolderTab);
        swap.setEnabled(t instanceof ComparisonTab c && (c.controller().left() != null
                || c.controller().right() != null));
        saveSession.setEnabled(cmpTab && ((ComparisonTab) t).controller().hasFiles());
        liveTail.setEnabled(cmpTab);
    }

    private void updateTitle() {
        AppTab t = current().orElse(null);
        boolean named = t instanceof ComparisonTab c ? c.controller().hasFiles() : t instanceof FolderTab;
        setTitle(named ? t.title() + " — " + Version.NAME : Version.NAME);
    }

    // --- window state ----------------------------------------------------------------------------

    private void restoreBounds() {
        Rectangle saved = settings.windowBounds();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        if (saved != null && screen.intersects(saved)) {
            setBounds(saved);
        } else {
            setSize(Math.min(1440, screen.width - 80), Math.min(900, screen.height - 80));
            setLocationRelativeTo(null);
        }
        if (settings.windowMaximized()) {
            setExtendedState(Frame.MAXIMIZED_BOTH);
        }
    }

    private void exit() {
        for (AppTab t : List.copyOf(open)) {
            tabs.setSelectedComponent(t.component());
            if (!t.confirmClose()) {
                return;
            }
        }
        boolean maximized = (getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
        settings.setWindowMaximized(maximized);
        if (!maximized) {
            settings.setWindowBounds(getBounds());
        }
        current().ifPresent(t -> {
            if (t instanceof ComparisonTab c) {
                settings.saveSessionOptions(c.controller().options());
            }
        });
        open.forEach(AppTab::dispose);
        settings.flush();
        dispose();
        System.exit(0);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static KeyStroke key(int code, int modifiers) {
        return KeyStroke.getKeyStroke(code, modifiers);
    }

    private static Action action(String name, Icon icon, KeyStroke key, String tooltip, Runnable run) {
        AbstractAction a = new AbstractAction(name, icon) {
            @Override
            public void actionPerformed(ActionEvent e) {
                run.run();
            }
        };
        if (key != null) {
            a.putValue(Action.ACCELERATOR_KEY, key);
        }
        a.putValue(Action.SHORT_DESCRIPTION, tooltip == null ? name : tooltip);
        return a;
    }
}
