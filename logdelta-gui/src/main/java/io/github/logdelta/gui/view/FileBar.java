// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.view;

import io.github.logdelta.gui.model.Source;
import io.github.logdelta.gui.util.Icons;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.TransferHandler;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static io.github.logdelta.gui.i18n.I18n.tr;

/// Two source fields (baseline and candidate) with browse buttons, a swap button and a button
/// for extra baseline runs. A path or an `http(s)` URL can be typed and confirmed with Enter,
/// chosen with the button, or a file dropped onto a field.
public final class FileBar extends JPanel {

    private final JTextField leftField = new JTextField();
    private final JTextField rightField = new JTextField();
    private final JButton baselines = new JButton(tr("+ Baselines"));

    /// @param onLeft      called with a source entered or dropped for the baseline
    /// @param onRight     called with a source entered or dropped for the candidate
    /// @param browseLeft  opens a file chooser for the baseline
    /// @param browseRight opens a file chooser for the candidate
    /// @param onSwap      swaps the sources
    /// @param onBaselines opens the menu of extra baseline runs (anchored at the given button)
    public FileBar(Consumer<Source> onLeft, Consumer<Source> onRight, Runnable browseLeft, Runnable browseRight,
                   Runnable onSwap, Consumer<JButton> onBaselines) {
        super(new GridBagLayout());
        setName("tour.filebar");
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 4, 0, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        add(label(tr("Baseline")), c);
        c.gridx = 1;
        c.weightx = 1;
        add(field(leftField, onLeft, tr("The known-good log (left)")), c);
        c.gridx = 2;
        c.weightx = 0;
        add(button(Icons.open(), tr("Choose the baseline log"), browseLeft), c);

        c.gridx = 3;
        JButton swap = button(Icons.swap(), tr("Swap baseline and candidate (Ctrl+Shift+S)"), onSwap);
        add(swap, c);

        c.gridx = 4;
        add(label(tr("Candidate")), c);
        c.gridx = 5;
        c.weightx = 1;
        add(field(rightField, onRight, tr("The log to check (right)")), c);
        c.gridx = 6;
        c.weightx = 0;
        add(button(Icons.open(), tr("Choose the candidate log"), browseRight), c);

        c.gridx = 7;
        baselines.setFocusable(false);
        baselines.setToolTipText("<html>" + tr("Add more known-good runs. A difference that another good run "
                + "shows too is treated as normal variation instead of a change.") + "</html>");
        baselines.addActionListener(_ -> onBaselines.accept(baselines));
        add(baselines, c);
    }

    /// Updates the label of the extra-baselines button.
    public void setBaselineCount(int n) {
        baselines.setText(n == 0 ? tr("+ Baselines") : tr("+{0} baseline(s)", n));
    }

    /// Shows the given sources without triggering callbacks.
    public void setSources(Source left, Source right) {
        show(leftField, left);
        show(rightField, right);
    }

    private static void show(JTextField f, Source s) {
        f.setText(s == null ? "" : s instanceof Source.Text t ? "\u2039" + t.displayName() + "\u203a" : s.description());
        f.setToolTipText(s == null ? f.getToolTipText() : s.description());
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private static JButton button(javax.swing.Icon icon, String tip, Runnable action) {
        JButton b = new JButton(icon);
        b.setToolTipText(tip);
        b.addActionListener(_ -> action.run());
        b.setFocusable(false);
        return b;
    }

    private JTextField field(JTextField f, Consumer<Source> target, String tip) {
        f.setToolTipText(tip + " \u2014 " + tr("type a path or URL and press Enter, or drop a file here"));
        f.getAccessibleContext().setAccessibleName(tip);
        f.putClientProperty("JTextField.placeholderText", "Drop a log file, or type a path or URL");
        f.addActionListener(_ -> {
            String text = f.getText().strip();
            if (!text.isEmpty() && !text.startsWith("\u2039")) {
                try {
                    target.accept(Source.parse(text));
                } catch (IllegalArgumentException e) { // includes InvalidPathException
                    javax.swing.JOptionPane.showMessageDialog(f, e.getMessage(), tr("Cannot open"),
                            javax.swing.JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        f.setTransferHandler(new FileDropHandler(files -> target.accept(Source.of(files.get(0))),
                f.getTransferHandler()));
        return f;
    }

    /// Accepts dropped files and passes them on; delegates everything else (text copy/paste) to the
    /// component's original handler.
    public static final class FileDropHandler extends TransferHandler {
        private final Consumer<List<Path>> onFiles;
        private final TransferHandler fallback;

        /// @param onFiles  receives the dropped files and folders (never empty)
        /// @param fallback handler for non-file transfers, may be `null`
        public FileDropHandler(Consumer<List<Path>> onFiles, TransferHandler fallback) {
            this.onFiles = onFiles;
            this.fallback = fallback;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return true;
            }
            return fallback != null && fallback.canImport(support);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                try {
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    List<Path> paths = files.stream().map(File::toPath)
                            .filter(p -> Files.isRegularFile(p) || Files.isDirectory(p)).toList();
                    if (paths.isEmpty()) {
                        return false;
                    }
                    onFiles.accept(paths);
                    return true;
                } catch (Exception _) {
                    return false;
                }
            }
            return fallback != null && fallback.importData(support);
        }

        @Override
        public int getSourceActions(JComponent c) {
            return fallback == null ? NONE : fallback.getSourceActions(c);
        }

        @Override
        protected java.awt.datatransfer.Transferable createTransferable(JComponent c) {
            return null;
        }

        @Override
        public void exportToClipboard(JComponent comp, java.awt.datatransfer.Clipboard clip, int action) {
            if (fallback != null) {
                fallback.exportToClipboard(comp, clip, action);
            }
        }
    }
}
