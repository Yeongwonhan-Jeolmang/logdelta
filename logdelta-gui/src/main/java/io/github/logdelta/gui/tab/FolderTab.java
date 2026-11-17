// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.tab;

import io.github.logdelta.core.analysis.InsightItem;
import io.github.logdelta.core.engine.DiffOptions;
import io.github.logdelta.core.folder.FolderComparer;
import io.github.logdelta.core.folder.FolderComparison;
import io.github.logdelta.core.folder.FolderEntry;
import io.github.logdelta.core.folder.FolderOptions;
import io.github.logdelta.core.report.FolderReports;
import io.github.logdelta.core.report.ReportFormat;
import io.github.logdelta.core.util.CancellationToken;
import io.github.logdelta.core.util.CancelledException;
import io.github.logdelta.core.util.LogText;
import io.github.logdelta.core.util.Text;
import io.github.logdelta.gui.theme.DiffPalette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/// Compares two folders of logs. Every file is listed with its similarity and its number of new
/// problems, files that need attention first; double-clicking a row opens that pair side by
/// side in a new tab.
public final class FolderTab extends JPanel implements AppTab {

    private final TabHost host;
    private final DiffOptions options;
    private final JTextField leftField = new JTextField();
    private final JTextField rightField = new JTextField();
    private final JTextField include = new JTextField(12);
    private final EntryModel model = new EntryModel();
    private final JTable table = new JTable(model);
    private final JTextArea details = new JTextArea();
    private final JLabel summary = new JLabel(" ");
    private FolderComparison result;
    private Worker running;

    private String statusMessage = "Choose two folders to compare";
    private boolean busy;
    private double fraction = -1;

    /// @param host    the main window
    /// @param options comparison options for every file pair
    public FolderTab(TabHost host, DiffOptions options) {
        super(new BorderLayout());
        this.host = host;
        this.options = options;

        JPanel bar = new JPanel(new GridBagLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 4, 0, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        addField(bar, c, 0, "Baseline folder", leftField, true);
        addField(bar, c, 3, "Candidate folder", rightField, false);
        c.gridx = 6;
        c.weightx = 0;
        bar.add(new JLabel("Files"), c);
        c.gridx = 7;
        include.setText("*");
        include.setToolTipText("Glob for the files to compare, e.g. *.log; separate several with commas");
        bar.add(include, c);
        c.gridx = 8;
        JButton compare = new JButton("Compare");
        compare.addActionListener(_ -> compare());
        bar.add(compare, c);

        table.setAutoCreateRowSorter(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setRowHeight(table.getRowHeight() + 6);
        table.setFillsViewportHeight(true);
        int[] widths = {60, 130, 90, 80};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            table.getColumnModel().getColumn(i).setMaxWidth(widths[i] * 2);
        }
        table.getColumnModel().getColumn(4).setPreferredWidth(480);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelected();
                }
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
        table.getActionMap().put("open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetails();
            }
        });

        details.setEditable(false);
        details.setLineWrap(false);
        details.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table),
                new JScrollPane(details));
        split.setResizeWeight(0.7);
        split.setBorder(null);

        summary.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));
        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.NORTH);
        north.add(summary, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    private void addField(JPanel bar, GridBagConstraints c, int x, String label, JTextField field, boolean left) {
        c.gridx = x;
        c.weightx = 0;
        JLabel l = new JLabel(label);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        bar.add(l, c);
        c.gridx = x + 1;
        c.weightx = 1;
        field.addActionListener(_ -> compare());
        field.putClientProperty("JTextField.placeholderText", "Folder path");
        bar.add(field, c);
        c.gridx = x + 2;
        c.weightx = 0;
        JButton browse = new JButton(io.github.logdelta.gui.util.Icons.open());
        browse.setToolTipText("Choose the " + label.toLowerCase(Locale.ROOT));
        browse.setFocusable(false);
        browse.addActionListener(_ -> {
            JFileChooser ch = host.chooser("Choose the " + label.toLowerCase(Locale.ROOT));
            ch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            ch.resetChoosableFileFilters();
            if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                field.setText(ch.getSelectedFile().toString());
                host.settings().setLastDirectory(ch.getSelectedFile().toPath());
                if (!leftField.getText().isBlank() && !rightField.getText().isBlank()) {
                    compare();
                } else if (left) {
                    rightField.requestFocusInWindow();
                }
            }
        });
        bar.add(browse, c);
    }

    /// Sets both folders and compares them.
    public void open(Path left, Path right) {
        leftField.setText(left.toString());
        rightField.setText(right.toString());
        compare();
    }

    /// Compares the folders in the fields.
    public void compare() {
        Path left;
        Path right;
        try {
            left = Path.of(leftField.getText().strip());
            right = Path.of(rightField.getText().strip());
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Compare folders", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!Files.isDirectory(left) || !Files.isDirectory(right)) {
            JOptionPane.showMessageDialog(this, "Choose two existing folders.", "Compare folders",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        cancel();
        List<String> globs = Arrays.stream(include.getText().split(","))
                .map(String::strip).filter(g -> !g.isEmpty() && !g.equals("*")).toList();
        running = new Worker(left, right, FolderOptions.DEFAULT.withInclude(globs));
        busy = true;
        fraction = 0;
        statusMessage = "Comparing folders";
        host.tabChanged(this);
        running.execute();
    }

    private void openSelected() {
        FolderEntry e = selected();
        if (e == null) {
            return;
        }
        if (e.left() == null || e.right() == null) {
            JOptionPane.showMessageDialog(this, e.name() + " exists only in the "
                    + (e.left() == null ? "candidate" : "baseline") + " folder.", "Open comparison",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        host.openInNewTab(e.left(), e.right(), options);
    }

    private FolderEntry selected() {
        int r = table.getSelectedRow();
        return r < 0 || result == null ? null : result.entries().get(r);
    }

    private void showDetails() {
        FolderEntry e = selected();
        if (e == null) {
            details.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(e.name()).append("  —  ").append(e.status().label());
        if (!e.message().isEmpty()) {
            sb.append(" (").append(e.message()).append(')');
        }
        sb.append('\n');
        if (e.left() != null) {
            sb.append("Baseline:  ").append(e.left()).append('\n');
        }
        if (e.right() != null) {
            sb.append("Candidate: ").append(e.right()).append('\n');
        }
        if (e.compared()) {
            sb.append(String.format(Locale.ROOT, "Similarity %.1f%%, %d change(s), %d resolved problem(s)%n",
                    e.similarity() * 100, e.stats().unexpectedChanges(), e.resolved()));
        }
        if (!e.newProblems().isEmpty()) {
            sb.append("\nNew problems:\n");
            for (InsightItem p : e.newProblems()) {
                sb.append(String.format(Locale.ROOT, "  %-5s line %-6d %s%n", p.severity().label(), p.lineNumber(),
                        Text.truncate(LogText.message(p.text()).strip(), 160)));
            }
        }
        if (e.left() != null && e.right() != null) {
            sb.append("\nDouble-click or press Enter to open this pair side by side.");
        }
        details.setText(sb.toString());
        details.setCaretPosition(0);
    }

    /// Copies a Markdown table of the folder comparison.
    public void copySummary() {
        if (result != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new StringSelection(FolderReports.render(result, ReportFormat.MARKDOWN)), null);
            statusMessage = "Markdown summary copied to clipboard";
            host.tabChanged(this);
        }
    }

    // --- AppTab ----------------------------------------------------------------------------------

    @Override
    public JComponent component() {
        return this;
    }

    @Override
    public String title() {
        String l = name(leftField.getText());
        String r = name(rightField.getText());
        return l.isEmpty() && r.isEmpty() ? "Folders" : l + "/ ↔ " + r + "/";
    }

    private static String name(String path) {
        try {
            Path p = Path.of(path.strip()).getFileName();
            return p == null ? path.strip() : p.toString();
        } catch (RuntimeException _) {
            return path.strip();
        }
    }

    @Override
    public String tooltip() {
        return "<html>Baseline: " + Text.escapeHtml(leftField.getText()) + "<br>Candidate: "
                + Text.escapeHtml(rightField.getText()) + "</html>";
    }

    @Override
    public String statusMessage() {
        return statusMessage;
    }

    @Override
    public String statusDetails() {
        return result == null ? "" : result.elapsedMillis() + " ms";
    }

    @Override
    public boolean isBusy() {
        return busy;
    }

    @Override
    public String busyStage() {
        return "Comparing folders";
    }

    @Override
    public double busyFraction() {
        return fraction;
    }

    @Override
    public void cancel() {
        if (running != null) {
            running.token.cancel();
        }
    }

    @Override
    public boolean confirmClose() {
        return true;
    }

    @Override
    public void dispose() {
        cancel();
    }

    // --- background work -------------------------------------------------------------------------

    private final class Worker extends SwingWorker<FolderComparison, Void> {
        final CancellationToken token = new CancellationToken();
        final Path left;
        final Path right;
        final FolderOptions folderOptions;

        Worker(Path left, Path right, FolderOptions folderOptions) {
            this.left = left;
            this.right = right;
            this.folderOptions = folderOptions;
        }

        @Override
        protected FolderComparison doInBackground() throws Exception {
            return new FolderComparer().compare(left, List.of(), right, options, folderOptions,
                    (_, f) -> SwingUtilities.invokeLater(() -> {
                        if (running == this) {
                            fraction = f;
                            host.tabChanged(FolderTab.this);
                        }
                    }), token);
        }

        @Override
        protected void done() {
            if (running != this) {
                return;
            }
            running = null;
            busy = false;
            try {
                result = get();
                model.fireTableDataChanged();
                summary.setText(String.format(Locale.ROOT,
                        "%d file(s): %d changed, %d identical, %d only in baseline, %d only in candidate"
                                + "  ·  %d new problem(s)", result.entries().size(),
                        result.count(FolderEntry.Status.CHANGED), result.count(FolderEntry.Status.IDENTICAL),
                        result.count(FolderEntry.Status.ONLY_LEFT), result.count(FolderEntry.Status.ONLY_RIGHT),
                        result.newProblems()));
                statusMessage = result.identical() ? "The folders match" : result.newProblems()
                        + " new problem(s) in " + result.count(FolderEntry.Status.CHANGED) + " changed file(s)";
                if (!result.entries().isEmpty()) {
                    table.setRowSelectionInterval(0, 0);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                statusMessage = "Cancelled";
            } catch (ExecutionException e) {
                statusMessage = e.getCause() instanceof CancelledException ? "Cancelled"
                        : "Cannot compare folders: " + e.getCause().getMessage();
            }
            host.tabChanged(FolderTab.this);
        }
    }

    // --- table -----------------------------------------------------------------------------------

    private final class EntryModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"New", "Status", "Similarity", "Changes", "File"};

        @Override
        public int getRowCount() {
            return result == null ? 0 : result.entries().size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            FolderEntry e = result.entries().get(r);
            return switch (c) {
                case 0 -> e.newProblemCount() == 0 ? "" : String.valueOf(e.newProblemCount());
                case 1 -> e.status().label();
                case 2 -> e.compared() ? String.format(Locale.ROOT, "%.1f%%", e.similarity() * 100) : "";
                case 3 -> e.compared() ? String.format(Locale.ROOT, "%,d", e.stats().unexpectedChanges()) : "";
                default -> e.name();
            };
        }
    }

    private final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, focus, r, c);
            setHorizontalAlignment(c == 0 || c == 2 || c == 3 ? RIGHT : LEFT);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            if (sel || result == null) {
                return this;
            }
            DiffPalette p = host.theme().palette();
            FolderEntry e = result.entries().get(r);
            Color fg = t.getForeground();
            if (c == 0) {
                fg = p.problem();
                setFont(getFont().deriveFont(Font.BOLD));
            } else if (c == 1) {
                fg = switch (e.status()) {
                    case CHANGED -> p.modifiedMarker();
                    case ONLY_LEFT, FAILED -> p.removedMarker();
                    case ONLY_RIGHT -> p.addedMarker();
                    default -> p.lineNumber();
                };
            }
            setForeground(fg);
            return this;
        }
    }
}
