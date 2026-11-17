// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.tour;

import io.github.logdelta.core.Version;
import io.github.logdelta.gui.settings.AppSettings;
import io.github.logdelta.gui.util.AppIcon;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/// A short first-run tour: the window is dimmed except for a spotlight on one part of it, and a
/// card next to the spotlight explains that part. The spotlight glides from step to step.
///
/// Targets are found by component name (`tour.toolbar`, `tour.filebar`, `tour.filter`,
/// `tour.sidebar`, `tour.tabs`); a step whose target is not showing is presented centred,
/// without a spotlight. Escape skips the tour at any time; it is offered again from
/// `Help > Take the Tour`.
public final class Tour extends JComponent {

    /// One step of the tour.
    ///
    /// @param target component name to spotlight, or `null` for a centred card
    /// @param title  short heading
    /// @param body   one or two sentences (HTML allowed)
    record Step(String target, String title, String body) {
    }

    private static final List<Step> STEPS = List.of(
            new Step(null, "Welcome to " + Version.NAME,
                    "LogDelta compares two runs of the same program and hides what always changes — "
                            + "timestamps, ids, durations — so the real differences stand out."),
            new Step("tour.filebar", "Pick the two runs",
                    "Drop logs here, or type a path or URL. <b>+ Baselines</b> adds more good runs: "
                            + "anything they also do is shown faded as normal variation."),
            new Step("tour.toolbar", "Move through the changes",
                    "<b>F7</b> jumps to the next change, <b>F8</b> to the next new problem. The toggles wrap "
                            + "long lines, show elapsed time, and turn on live tail for logs that are still "
                            + "being written."),
            new Step("tour.filter", "Narrow it down",
                    "Show only changes, only new problems or only errors, or type a word to keep the lines "
                            + "that contain it. <b>Ctrl+L</b> jumps here."),
            new Step("tour.sidebar", "Let it point the way",
                    "<b>Insights</b> lists new errors, <b>Timing</b> the phases that got slower, <b>Rules</b> "
                            + "suggests masks for noise, and <b>Notes</b> keeps your bookmarks (<b>Ctrl+B</b>)."),
            new Step(null, "That’s it",
                    "Save the comparison with your notes as a session (<b>Ctrl+S</b>). You can replay this "
                            + "tour from <b>Help › Take the Tour</b>."));

    private static final int PAD = 8;
    private static final int ANIMATION_MS = 220;

    private final JFrame frame;
    private final AppSettings settings;
    private final Runnable loadExample;
    private final Component previousGlass;
    private final JPanel card = new JPanel();
    private final JLabel heading = new JLabel();
    private final JLabel text = new JLabel();
    private final JLabel counter = new JLabel();
    private final JButton back = new JButton("Back");
    private final JButton next = new JButton("Next");
    private final JButton example = new JButton("Load the example");
    private int index;
    private Rectangle from;
    private Rectangle to;
    private Rectangle spot;
    private long animationStart;
    private final Timer animation;

    private Tour(JFrame frame, AppSettings settings, Runnable loadExample) {
        this.frame = frame;
        this.settings = settings;
        this.loadExample = loadExample;
        this.previousGlass = frame.getGlassPane();
        setLayout(null);
        setOpaque(false);
        // Swallow clicks and keys so the window underneath stays put during the tour.
        MouseAdapter swallow = new MouseAdapter() {
        };
        addMouseListener(swallow);
        addMouseMotionListener(swallow);
        addMouseWheelListener(swallow);
        setFocusable(true);
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "skip");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "back");
        getActionMap().put("skip", action(this::finish));
        getActionMap().put("next", action(() -> go(index + 1)));
        getActionMap().put("back", action(() -> go(index - 1)));

        buildCard();
        add(card);
        animation = new Timer(15, _ -> tick());
    }

    /// Shows the tour over `frame`.
    ///
    /// @param settings    marks the tour as shown when it ends
    /// @param loadExample loads the bundled example, so later steps have something to point at
    public static void show(JFrame frame, AppSettings settings, Runnable loadExample) {
        Tour tour = new Tour(frame, settings, loadExample);
        frame.setGlassPane(tour);
        tour.setVisible(true);
        tour.requestFocusInWindow();
        tour.go(0);
    }

    private void buildCard() {
        card.setLayout(new BorderLayout(0, 10));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border(), 1, true),
                BorderFactory.createEmptyBorder(16, 18, 14, 18)));
        Color bg = UIManager.getColor("Panel.background");
        card.setBackground(bg == null ? Color.WHITE : bg);

        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        BufferedImage icon = AppIcon.render(28);
        head.add(new JLabel(new javax.swing.ImageIcon(icon)), BorderLayout.WEST);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() + 3f));
        head.add(heading, BorderLayout.CENTER);
        card.add(head, BorderLayout.NORTH);

        text.setVerticalAlignment(JLabel.TOP);
        card.add(text, BorderLayout.CENTER);

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        JButton skip = new JButton("Skip tour");
        skip.setBorderPainted(false);
        skip.setContentAreaFilled(false);
        skip.addActionListener(_ -> finish());
        back.addActionListener(_ -> go(index - 1));
        next.addActionListener(_ -> go(index + 1));
        example.setToolTipText("Open the bundled example so the next steps have something to show");
        example.addActionListener(_ -> {
            loadExample.run();
            example.setEnabled(false);
            // Give the example a moment to load, then move on.
            Timer t = new Timer(600, _ -> {
                if (index == 0 && isVisible()) {
                    go(1);
                }
            });
            t.setRepeats(false);
            t.start();
        });
        counter.setForeground(muted());
        buttons.add(skip);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(counter);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(example);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(back);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(next);
        card.add(buttons, BorderLayout.SOUTH);
    }

    private void go(int i) {
        if (i < 0) {
            return;
        }
        if (i >= STEPS.size()) {
            finish();
            return;
        }
        index = i;
        Step step = STEPS.get(i);
        heading.setText(step.title());
        text.setText("<html><div style='width:330px'>" + step.body() + "</div></html>");
        counter.setText(dots(i));
        back.setVisible(i > 0);
        next.setText(i == STEPS.size() - 1 ? "Done" : "Next");
        example.setVisible(i == 0);
        example.setEnabled(true);
        SwingUtilities.getRootPane(next).setDefaultButton(next);

        Rectangle target = step.target() == null ? null : find(step.target());
        from = spot;
        to = target == null ? null : grow(target);
        animationStart = System.currentTimeMillis();
        animation.restart();
        layoutCard();
        repaint();
        next.requestFocusInWindow();
    }

    private static String dots(int i) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < STEPS.size(); k++) {
            sb.append(k == i ? '●' : '○');
        }
        return sb.toString();
    }

    private void tick() {
        double t = Math.min(1.0, (System.currentTimeMillis() - animationStart) / (double) ANIMATION_MS);
        double e = 1 - Math.pow(1 - t, 3); // ease out
        if (to == null) {
            spot = null;
        } else if (from == null) {
            spot = to;
        } else {
            spot = new Rectangle(lerp(from.x, to.x, e), lerp(from.y, to.y, e), lerp(from.width, to.width, e),
                    lerp(from.height, to.height, e));
        }
        layoutCard();
        repaint();
        if (t >= 1) {
            animation.stop();
        }
    }

    private static int lerp(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    /// Places the card below the spotlight, or above it, or beside it, or centred.
    private void layoutCard() {
        Dimension size = card.getPreferredSize();
        int w = size.width;
        int h = size.height;
        int x;
        int y;
        Rectangle r = to;
        if (r == null) {
            x = (getWidth() - w) / 2;
            y = (getHeight() - h) / 2;
        } else if (r.y + r.height + 12 + h < getHeight()) {
            x = r.x + Math.min(24, r.width / 4);
            y = r.y + r.height + 12;
        } else if (r.y - 12 - h > 0) {
            x = r.x + Math.min(24, r.width / 4);
            y = r.y - 12 - h;
        } else if (r.x - 16 - w > 0) {
            x = r.x - 16 - w;
            y = r.y + 24;
        } else {
            x = (getWidth() - w) / 2;
            y = (getHeight() - h) / 2;
        }
        x = Math.clamp(x, 12, Math.max(12, getWidth() - w - 12));
        y = Math.clamp(y, 12, Math.max(12, getHeight() - h - 12));
        card.setBounds(x, y, w, h);
        card.revalidate();
    }

    private Rectangle find(String name) {
        Component c = find(frame.getContentPane(), name);
        if (c == null || !c.isShowing() || c.getWidth() == 0) {
            return null;
        }
        return SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), this);
    }

    private static Component find(Container root, String name) {
        for (Component c : root.getComponents()) {
            if (name.equals(c.getName()) && c.isShowing()) {
                return c;
            }
            if (c instanceof Container cc) {
                Component found = find(cc, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Rectangle grow(Rectangle r) {
        Rectangle g = new Rectangle(r.x - PAD, r.y - PAD, r.width + 2 * PAD, r.height + 2 * PAD);
        return g.intersection(new Rectangle(4, 4, getWidth() - 8, getHeight() - 8));
    }

    private void finish() {
        animation.stop();
        settings.setTourShown(true);
        setVisible(false);
        frame.setGlassPane(previousGlass);
        previousGlass.setVisible(false);
        frame.getRootPane().setDefaultButton(null);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Area dim = new Area(new Rectangle(0, 0, getWidth(), getHeight()));
            RoundRectangle2D hole = spot == null ? null
                    : new RoundRectangle2D.Double(spot.x, spot.y, spot.width, spot.height, 14, 14);
            if (hole != null) {
                dim.subtract(new Area(hole));
            }
            g.setComposite(AlphaComposite.SrcOver.derive(0.55f));
            g.setColor(new Color(10, 14, 24));
            g.fill(dim);
            g.setComposite(AlphaComposite.SrcOver);
            if (hole != null) {
                g.setColor(new Color(0xFF, 0xB1, 0x3B));
                g.setStroke(new BasicStroke(2f));
                g.draw(hole);
            }
        } finally {
            g.dispose();
        }
    }

    private static Color border() {
        Color c = UIManager.getColor("nimbusBorder");
        return c == null ? Color.GRAY : c;
    }

    private static Color muted() {
        Color c = UIManager.getColor("textInactiveText");
        return c == null ? Color.GRAY : c;
    }

    private static AbstractAction action(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }
}
