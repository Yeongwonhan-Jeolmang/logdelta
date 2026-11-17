// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Hana Eun-Seo

package io.github.logdelta.gui.util;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// The application icon, drawn in code so that every size is crisp.
///
/// The mark is a delta (Δ) on a deep navy tile. Inside it sit three log lines; the middle one is
/// amber and shifted, the one line that changed. Small sizes drop the grey lines and keep only
/// the delta and the amber line.
///
/// Running this class writes the icon files used by the installers:
///
/// ```
/// java -cp logdelta.jar io.github.logdelta.gui.util.AppIcon packaging/icons
/// ```
///
/// which produces `logdelta.png` (512 px, Linux), `logdelta-128.png` (documentation),
/// `logdelta.ico` (Windows) and `logdelta.icns` (macOS).
public final class AppIcon {

    /// Sizes offered to the window manager.
    private static final int[] WINDOW_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    private static final Color TILE_TOP = new Color(0x2E3F5E);
    private static final Color TILE_BOTTOM = new Color(0x18213A);
    private static final Color DELTA = new Color(0xF4F6FA);
    private static final Color LINE = new Color(0x8EA2C2);
    private static final Color CHANGED = new Color(0xFFB13B);

    private AppIcon() {
    }

    /// Window icons in several sizes.
    public static List<Image> images() {
        List<Image> out = new ArrayList<>();
        for (int s : WINDOW_SIZES) {
            out.add(render(s));
        }
        return out;
    }

    /// Renders the icon at `size` × `size` pixels.
    public static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            paint(g, size);
        } finally {
            g.dispose();
        }
        return img;
    }

    /// Paints the icon into a `size` × `size` square at the origin.
    public static void paint(Graphics2D g, double size) {
        double s = size;
        double inset = s * 0.04;
        Shape tile = new RoundRectangle2D.Double(inset, inset, s - 2 * inset, s - 2 * inset, s * 0.42, s * 0.42);
        g.setPaint(new GradientPaint(0, (float) inset, TILE_TOP, 0, (float) (s - inset), TILE_BOTTOM));
        g.fill(tile);

        // The delta.
        Path2D delta = new Path2D.Double();
        delta.moveTo(s * 0.50, s * 0.20);
        delta.lineTo(s * 0.83, s * 0.77);
        delta.lineTo(s * 0.17, s * 0.77);
        delta.closePath();

        boolean tiny = size < 28;
        // Log lines, clipped to the inside of the delta.
        Shape oldClip = g.getClip();
        g.clip(delta);
        double h = s * (tiny ? 0.11 : 0.062);
        if (!tiny) {
            bar(g, s * 0.24, s * 0.465, s * 0.40, h, LINE);
            bar(g, s * 0.30, s * 0.665, s * 0.46, h, LINE);
        }
        bar(g, tiny ? s * 0.30 : s * 0.38, tiny ? s * 0.53 : s * 0.565, s * (tiny ? 0.40 : 0.42), h, CHANGED);
        g.setClip(oldClip);

        g.setColor(DELTA);
        g.setStroke(new BasicStroke((float) (s * (tiny ? 0.09 : 0.065)), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g.draw(delta);
    }

    private static void bar(Graphics2D g, double x, double y, double w, double h, Color c) {
        g.setColor(c);
        g.fill(new RoundRectangle2D.Double(x, y - h / 2, w, h, h, h));
    }

    // --- installer icon files ------------------------------------------------------------------

    /// Writes PNG, ICO and ICNS files into the folder given as the first argument.
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "packaging/icons");
        Files.createDirectories(dir);
        ImageIO.write(render(512), "png", dir.resolve("logdelta.png").toFile());
        ImageIO.write(render(128), "png", dir.resolve("logdelta-128.png").toFile());
        try (OutputStream out = Files.newOutputStream(dir.resolve("logdelta.ico"))) {
            writeIco(out, new int[] {16, 24, 32, 48, 64, 128, 256});
        }
        try (OutputStream out = Files.newOutputStream(dir.resolve("logdelta.icns"))) {
            writeIcns(out);
        }
        System.out.println("Wrote icons to " + dir.toAbsolutePath());
    }

    private static byte[] png(int size) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(render(size), "png", bytes);
        return bytes.toByteArray();
    }

    /// An ICO file whose entries are PNG images (supported since Windows Vista).
    static void writeIco(OutputStream out, int[] sizes) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int s : sizes) {
            images.add(png(s));
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeLe16(buf, 0);
        writeLe16(buf, 1);
        writeLe16(buf, sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            buf.write(s >= 256 ? 0 : s);
            buf.write(s >= 256 ? 0 : s);
            buf.write(0);
            buf.write(0);
            writeLe16(buf, 1);
            writeLe16(buf, 32);
            writeLe32(buf, images.get(i).length);
            writeLe32(buf, offset);
            offset += images.get(i).length;
        }
        for (byte[] img : images) {
            buf.write(img);
        }
        out.write(buf.toByteArray());
    }

    /// An ICNS file with PNG entries for 16 to 1024 pixels.
    static void writeIcns(OutputStream out) throws IOException {
        String[][] types = {{"icp4", "16"}, {"icp5", "32"}, {"icp6", "64"}, {"ic07", "128"}, {"ic08", "256"},
            {"ic09", "512"}, {"ic10", "1024"}};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        for (String[] t : types) {
            byte[] img = png(Integer.parseInt(t[1]));
            d.writeBytes(t[0]);
            d.writeInt(img.length + 8);
            d.write(img);
        }
        DataOutputStream o = new DataOutputStream(out);
        o.writeBytes("icns");
        o.writeInt(body.size() + 8);
        body.writeTo(o);
        o.flush();
    }

    private static void writeLe16(ByteArrayOutputStream b, int v) {
        b.write(v & 0xFF);
        b.write((v >>> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream b, int v) {
        writeLe16(b, v & 0xFFFF);
        writeLe16(b, (v >>> 16) & 0xFFFF);
    }
}
