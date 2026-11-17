// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.util;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/// Small line icons drawn with Java2D, so they stay sharp at any display scale and follow the
/// theme's text colour. All icons are drawn on a 16x16 grid.
public final class Icons {

    private Icons() {
    }

    /// Folder: open a file.
    public static Icon open() {
        return icon(g -> {
            Path2D p = new Path2D.Double();
            p.moveTo(2, 4);
            p.lineTo(6, 4);
            p.lineTo(7.5, 5.5);
            p.lineTo(14, 5.5);
            p.lineTo(14, 13);
            p.lineTo(2, 13);
            p.closePath();
            g.draw(p);
        });
    }

    /// Two opposing arrows: swap sides.
    public static Icon swap() {
        return icon(g -> {
            g.draw(new Line2D.Double(2, 5.5, 13, 5.5));
            g.draw(arrowHead(13, 5.5, true));
            g.draw(new Line2D.Double(3, 10.5, 14, 10.5));
            g.draw(arrowHead(3, 10.5, false));
        });
    }

    /// Circular arrow: compare again.
    public static Icon reload() {
        return icon(g -> {
            g.draw(new Arc2D.Double(2.5, 2.5, 11, 11, 60, 280, Arc2D.OPEN));
            Path2D p = new Path2D.Double();
            p.moveTo(13.2, 1.8);
            p.lineTo(11.2, 5.4);
            p.lineTo(7.6, 4.0);
            g.draw(p);
        });
    }

    /// Chevron up: previous change.
    public static Icon previous() {
        return icon(g -> {
            Path2D p = new Path2D.Double();
            p.moveTo(3.5, 10.5);
            p.lineTo(8, 5.5);
            p.lineTo(12.5, 10.5);
            g.draw(p);
        });
    }

    /// Chevron down: next change.
    public static Icon next() {
        return icon(g -> {
            Path2D p = new Path2D.Double();
            p.moveTo(3.5, 5.5);
            p.lineTo(8, 10.5);
            p.lineTo(12.5, 5.5);
            g.draw(p);
        });
    }

    /// Magnifier: search.
    public static Icon search() {
        return icon(g -> {
            g.draw(new Ellipse2D.Double(2.5, 2.5, 8, 8));
            g.draw(new Line2D.Double(9.5, 9.5, 13.5, 13.5));
        });
    }

    /// Tray with an outgoing arrow: export.
    public static Icon export() {
        return icon(g -> {
            Path2D tray = new Path2D.Double();
            tray.moveTo(2.5, 9);
            tray.lineTo(2.5, 13.5);
            tray.lineTo(13.5, 13.5);
            tray.lineTo(13.5, 9);
            g.draw(tray);
            g.draw(new Line2D.Double(8, 2.5, 8, 10));
            g.draw(arrowHeadUp(8, 2.5));
        });
    }

    /// Sliders: rules and options.
    public static Icon rules() {
        return icon(g -> {
            g.draw(new Line2D.Double(2, 4.5, 14, 4.5));
            g.draw(new Line2D.Double(2, 11.5, 14, 11.5));
            g.fill(new Ellipse2D.Double(8.5, 2.5, 4, 4));
            g.fill(new Ellipse2D.Double(3.5, 9.5, 4, 4));
        });
    }

    /// Stacked lines with a fold: collapse unchanged regions.
    public static Icon collapse() {
        return icon(g -> {
            g.draw(new Line2D.Double(2.5, 3, 13.5, 3));
            g.draw(new Line2D.Double(2.5, 13, 13.5, 13));
            Path2D p = new Path2D.Double();
            p.moveTo(5, 6);
            p.lineTo(8, 8);
            p.lineTo(11, 6);
            p.moveTo(5, 10);
            p.lineTo(8, 8);
            p.lineTo(11, 10);
            g.draw(p);
        });
    }

    /// Document with a tilde: show normalized text.
    public static Icon normalized() {
        return icon(g -> {
            g.draw(new RoundRectangle2D.Double(2.5, 2.5, 11, 11, 3, 3));
            Path2D wave = new Path2D.Double();
            wave.moveTo(4.5, 8.5);
            wave.curveTo(6, 6, 7, 6, 8, 8);
            wave.curveTo(9, 10, 10, 10, 11.5, 7.5);
            g.draw(wave);
        });
    }

    /// Plus in a tab outline: new tab.
    public static Icon newTab() {
        return icon(g -> {
            Path2D p = new Path2D.Double();
            p.moveTo(1.5, 13.5);
            p.lineTo(1.5, 4.5);
            p.lineTo(9, 4.5);
            p.lineTo(10.5, 6.5);
            p.lineTo(14.5, 6.5);
            p.lineTo(14.5, 13.5);
            p.closePath();
            g.draw(p);
            g.draw(new Line2D.Double(8, 8, 8, 12));
            g.draw(new Line2D.Double(6, 10, 10, 10));
        });
    }

    /// Text lines with a return arrow: wrap long lines.
    public static Icon wrap() {
        return icon(g -> {
            g.draw(new Line2D.Double(2, 3.5, 14, 3.5));
            Path2D p = new Path2D.Double();
            p.moveTo(2, 8);
            p.lineTo(11.5, 8);
            p.curveTo(15, 8, 15, 12.5, 11.5, 12.5);
            p.lineTo(8, 12.5);
            g.draw(p);
            g.draw(arrowHead(8, 12.5, false));
            g.draw(new Line2D.Double(2, 12.5, 5, 12.5));
        });
    }

    /// Clock face: elapsed time.
    public static Icon clock() {
        return icon(g -> {
            g.draw(new Ellipse2D.Double(2, 2, 12, 12));
            g.draw(new Line2D.Double(8, 8, 8, 4.5));
            g.draw(new Line2D.Double(8, 8, 10.5, 9.5));
        });
    }

    /// A dot with two ripples: live tail.
    public static Icon live() {
        return icon(g -> {
            g.fill(new Ellipse2D.Double(6, 6, 4, 4));
            g.draw(new Arc2D.Double(3, 3, 10, 10, -50, 100, Arc2D.OPEN));
            g.draw(new Arc2D.Double(3, 3, 10, 10, 130, 100, Arc2D.OPEN));
            g.draw(new Arc2D.Double(0.5, 0.5, 15, 15, -45, 90, Arc2D.OPEN));
            g.draw(new Arc2D.Double(0.5, 0.5, 15, 15, 135, 90, Arc2D.OPEN));
        });
    }

    /// Two stacked folders: folder comparison.
    public static Icon folder() {
        return icon(g -> {
            Path2D p = new Path2D.Double();
            p.moveTo(2, 5);
            p.lineTo(5.5, 5);
            p.lineTo(7, 6.5);
            p.lineTo(13, 6.5);
            p.lineTo(13, 12.5);
            p.lineTo(2, 12.5);
            p.closePath();
            g.draw(p);
            g.draw(new Line2D.Double(4, 3, 7.5, 3));
            g.draw(new Line2D.Double(7.5, 3, 9, 4.5));
            g.draw(new Line2D.Double(9, 4.5, 15, 4.5));
            g.draw(new Line2D.Double(15, 4.5, 15, 10.5));
        });
    }

    private static Path2D arrowHead(double x, double y, boolean right) {
        double d = right ? -3 : 3;
        Path2D p = new Path2D.Double();
        p.moveTo(x + d, y - 3);
        p.lineTo(x, y);
        p.lineTo(x + d, y + 3);
        return p;
    }

    private static Path2D arrowHeadUp(double x, double y) {
        Path2D p = new Path2D.Double();
        p.moveTo(x - 3, y + 3);
        p.lineTo(x, y);
        p.lineTo(x + 3, y + 3);
        return p;
    }

    private static Icon icon(Consumer<Graphics2D> painter) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    g2.translate(x, y);
                    Color fg = c != null && c.isEnabled() ? UIManager.getColor("text")
                            : UIManager.getColor("nimbusDisabledText");
                    g2.setColor(fg != null ? fg : Color.DARK_GRAY);
                    g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    painter.accept(g2);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }
}
