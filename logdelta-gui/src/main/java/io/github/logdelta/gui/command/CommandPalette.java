// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.command;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

import static io.github.logdelta.gui.i18n.I18n.tr;

/// A searchable list of every menu command (Ctrl+Shift+P, ⌘⇧P on macOS): type a few letters of
/// a command, press Enter to run it.
///
/// Commands come from the menu bar, so the palette always offers exactly what the menus offer,
/// including check boxes (shown with their state) and the current theme or filter choices.
/// Recently run commands are listed first while the query is empty.
public final class CommandPalette extends JDialog {

    /// Most recently used commands (by full name), most recent first; shared by all windows.
    private static final Deque<String> RECENT = new ArrayDeque<>();
    private static final int MAX_RECENT = 8;

    private final List<MenuCommands.Entry> all;
    private final JTextField query = new JTextField();
    private final DefaultListModel<MenuCommands.Entry> model = new DefaultListModel<>();
    private final JList<MenuCommands.Entry> list = new JList<>(model);

    private CommandPalette(Window owner, JMenuBar menus) {
        super(owner, tr("Command palette"), ModalityType.APPLICATION_MODAL);
        setUndecorated(true);
        this.all = MenuCommands.collect(menus);

        JPanel root = new JPanel(new BorderLayout(0, 6));
        Color border = UIManager.getColor("nimbusBorder");
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border == null ? Color.GRAY : border),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        query.putClientProperty("JTextField.placeholderText", tr("Type a command…"));
        query.getAccessibleContext().setAccessibleName(tr("Search commands"));
        query.setFont(query.getFont().deriveFont(query.getFont().getSize2D() + 2f));
        root.add(query, BorderLayout.NORTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.setVisibleRowCount(12);
        list.getAccessibleContext().setAccessibleName(tr("Commands"));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    runSelected();
                }
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        root.add(scroll, BorderLayout.CENTER);
        JLabel hint = new JLabel(tr("↑↓ to choose · Enter to run · Esc to close"));
        hint.setFont(hint.getFont().deriveFont(Math.max(10f, hint.getFont().getSize2D() - 1f)));
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        root.add(hint, BorderLayout.SOUTH);
        setContentPane(root);

        query.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter();
            }
        });
        bind("ESCAPE", this::dispose);
        bind("ENTER", this::runSelected);
        bind("DOWN", () -> move(1));
        bind("UP", () -> move(-1));
        bind("PAGE_DOWN", () -> move(10));
        bind("PAGE_UP", () -> move(-10));
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                dispose();
            }
        });
        refilter();
        setSize(new Dimension(Math.min(620, Math.max(420, owner.getWidth() / 2)), 420));
        Point p = owner.getLocationOnScreen();
        setLocation(p.x + (owner.getWidth() - getWidth()) / 2, p.y + 80);
    }

    /// Opens the palette over `owner` and runs the chosen command.
    public static void show(Window owner, JMenuBar menus) {
        new CommandPalette(owner, menus).setVisible(true);
    }

    /// Entries matching `text`, best first; disabled commands sort last. Package-private for tests.
    static List<MenuCommands.Entry> filter(List<MenuCommands.Entry> entries, String text) {
        record Scored(MenuCommands.Entry entry, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (MenuCommands.Entry e : entries) {
            int s;
            if (text.isBlank()) {
                int recent = indexOf(RECENT, e.fullName());
                s = recent >= 0 ? 10_000 - recent : 0;
            } else {
                s = Math.max(MenuCommands.score(text, e.name()) + 50, MenuCommands.score(text, e.fullName()));
                if (MenuCommands.score(text, e.name()) < 0 && MenuCommands.score(text, e.fullName()) < 0) {
                    continue;
                }
            }
            scored.add(new Scored(e, s + (e.enabled() ? 100_000 : 0)));
        }
        scored.sort(Comparator.comparingInt((Scored x) -> -x.score()));
        return scored.stream().map(Scored::entry).toList();
    }

    private static int indexOf(Deque<String> deque, String value) {
        int i = 0;
        for (String s : deque) {
            if (s.equals(value)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private void refilter() {
        model.clear();
        filter(all, query.getText()).forEach(model::addElement);
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    private void move(int delta) {
        if (model.isEmpty()) {
            return;
        }
        int i = Math.clamp(list.getSelectedIndex() + delta, 0, model.size() - 1);
        list.setSelectedIndex(i);
        list.ensureIndexIsVisible(i);
    }

    private void runSelected() {
        MenuCommands.Entry e = list.getSelectedValue();
        if (e == null) {
            return;
        }
        if (!e.enabled()) {
            java.awt.Toolkit.getDefaultToolkit().beep();
            return;
        }
        RECENT.remove(e.fullName());
        RECENT.addFirst(e.fullName());
        while (RECENT.size() > MAX_RECENT) {
            RECENT.removeLast();
        }
        dispose();
        SwingUtilities.invokeLater(e::run);
    }

    private void bind(String key, Runnable r) {
        String name = "palette." + key;
        query.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        query.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        getRootPane().getActionMap().put(name, query.getActionMap().get(name));
    }

    /// Name on the left, menu path below it, shortcut on the right.
    private static final class Renderer extends JPanel implements ListCellRenderer<MenuCommands.Entry> {
        private final JLabel name = new JLabel();
        private final JLabel path = new JLabel();
        private final JLabel key = new JLabel();

        Renderer() {
            super(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            JPanel text = new JPanel(new BorderLayout());
            text.setOpaque(false);
            text.add(name, BorderLayout.NORTH);
            text.add(path, BorderLayout.SOUTH);
            add(text, BorderLayout.CENTER);
            add(key, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends MenuCommands.Entry> list,
                                                      MenuCommands.Entry e, int index, boolean selected,
                                                      boolean focus) {
            name.setText((e.checked() ? "✓ " : "") + e.name());
            path.setText(String.join(" › ", e.path()));
            key.setText(MenuCommands.describe(e.accelerator()));
            Color bg = selected ? list.getSelectionBackground() : list.getBackground();
            Color fg = selected ? list.getSelectionForeground() : list.getForeground();
            Color muted = UIManager.getColor("Label.disabledForeground");
            setBackground(bg);
            name.setForeground(e.enabled() ? fg : muted);
            name.setFont(list.getFont().deriveFont(e.enabled() ? Font.PLAIN : Font.ITALIC));
            path.setForeground(selected ? fg : muted);
            path.setFont(list.getFont().deriveFont(Math.max(10f, list.getFont().getSize2D() - 2f)));
            key.setForeground(selected ? fg : muted);
            getAccessibleContext().setAccessibleName(e.fullName()
                    + (e.accelerator() == null ? "" : ", " + MenuCommands.describe(e.accelerator()))
                    + (e.enabled() ? "" : ", " + tr("not available now")));
            return this;
        }
    }
}
