// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.sidebar;

import io.github.logdelta.core.session.Bookmark;
import io.github.logdelta.core.util.Text;
import io.github.logdelta.gui.theme.DiffPalette;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import static io.github.logdelta.gui.i18n.I18n.tr;

/// Sidebar tab for the reviewer's own annotations: free-form notes about the comparison and the
/// list of bookmarked lines. Both are saved with the session (`File > Save Session`).
public final class NotesPanel extends JPanel {

    /// Callbacks for edits made in the panel.
    public interface Listener {
        /// Navigate to a bookmark.
        void goTo(Bookmark b);

        /// Edit the note of a bookmark.
        void edit(Bookmark b);

        /// Remove a bookmark.
        void remove(Bookmark b);

        /// The free-form notes changed.
        void notesChanged(String notes);
    }

    private final DefaultListModel<Bookmark> model = new DefaultListModel<>();
    private final JList<Bookmark> list = new JList<>(model);
    private final JTextArea notes = new JTextArea(6, 20);
    private final JLabel caption = Section.caption(tr("Bookmarks"));
    private DiffPalette palette = DiffPalette.LIGHT;
    private boolean updating;

    /// @param listener receives edits
    public NotesPanel(Listener listener) {
        super(new BorderLayout());
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.putClientProperty("JTextArea.placeholderText", "Notes about this comparison");
        notes.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        notes.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }

            private void changed() {
                if (!updating) {
                    listener.notesChanged(notes.getText());
                }
            }
        });

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Bookmark b = list.getSelectedValue();
                if (b == null) {
                    return;
                }
                if (e.getClickCount() == 2) {
                    listener.edit(b);
                } else {
                    listener.goTo(b);
                }
            }
        });
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null && !updating) {
                listener.goTo(list.getSelectedValue());
            }
        });

        JButton edit = new JButton(tr("Edit note…"));
        edit.addActionListener(_ -> {
            if (list.getSelectedValue() != null) {
                listener.edit(list.getSelectedValue());
            }
        });
        JButton remove = new JButton(tr("Remove"));
        remove.addActionListener(_ -> {
            if (list.getSelectedValue() != null) {
                listener.remove(list.getSelectedValue());
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        buttons.add(edit);
        buttons.add(remove);

        JPanel top = new JPanel(new BorderLayout());
        JLabel notesCaption = Section.caption(tr("Notes"));
        notesCaption.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        top.add(notesCaption, BorderLayout.NORTH);
        top.add(new JScrollPane(notes), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        caption.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        bottom.add(caption, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        bottom.add(scroll, BorderLayout.CENTER);
        JLabel hint = new JLabel(tr("<html>Ctrl+B or double-click a line number to bookmark it.</html>"));
        hint.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        hint.setForeground(Section.muted());
        JPanel south = new JPanel(new BorderLayout());
        south.add(hint, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        bottom.add(south, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.35);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
    }

    public void setPalette(DiffPalette palette) {
        this.palette = palette;
        repaint();
    }

    /// Shows bookmarks, keeping the selection where possible.
    public void setBookmarks(List<Bookmark> bookmarks) {
        updating = true;
        try {
            Bookmark selected = list.getSelectedValue();
            model.clear();
            bookmarks.forEach(model::addElement);
            if (selected != null) {
                for (int i = 0; i < model.size(); i++) {
                    Bookmark b = model.get(i);
                    if (b.side() == selected.side() && b.line() == selected.line()) {
                        list.setSelectedIndex(i);
                    }
                }
            }
            caption.setText(tr("Bookmarks") + "  (" + bookmarks.size() + ")");
        } finally {
            updating = false;
        }
    }

    /// Shows notes without notifying the listener.
    public void setNotes(String text) {
        updating = true;
        try {
            if (!notes.getText().equals(text)) {
                notes.setText(text);
            }
        } finally {
            updating = false;
        }
    }

    /// Two-line cell: side and line number, then the note (or the line text).
    private final class Renderer extends JPanel implements ListCellRenderer<Bookmark> {
        private final JLabel head = new JLabel();
        private final JLabel body = new JLabel();

        Renderer() {
            super(new BorderLayout(0, 2));
            setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 8));
            add(head, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
            head.setFont(head.getFont().deriveFont(Font.BOLD));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Bookmark> l, Bookmark b, int index,
                                                      boolean selected, boolean focus) {
            head.setText(b.side() == Bookmark.Side.LEFT ? tr("Baseline line {0}", b.line()) : tr("Candidate line {0}", b.line()));
            String text = b.note().isBlank() ? b.text() : b.note().replace('\n', ' ');
            body.setText(Text.truncate(text.strip(), 90));
            body.setFont(b.note().isBlank() ? l.getFont().deriveFont(Font.ITALIC) : l.getFont());
            setToolTipText(b.text().isEmpty() ? null : b.text());
            setBackground(selected ? l.getSelectionBackground() : l.getBackground());
            head.setForeground(selected ? l.getSelectionForeground() : palette.bookmark());
            body.setForeground(selected ? l.getSelectionForeground() : l.getForeground());
            return this;
        }
    }
}
