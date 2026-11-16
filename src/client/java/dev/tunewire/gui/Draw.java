package dev.tunewire.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Shared look: dark translucent panels with a green accent, 1px-cut "rounded" corners, pixel icons. */
public final class Draw {
    public static final int ACCENT = 0xFF1ED760;
    public static final int ACCENT_DIM = 0xFF168D45;
    public static final int PANEL = 0xF0121318;
    public static final int SIDEBAR = 0xF0181920;
    public static final int CARD = 0xFF1D1E26;
    public static final int CARD_HOVER = 0xFF262833;
    public static final int BORDER = 0xFF2E3040;
    public static final int TRACK = 0xFF3A3D4C;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int MUTED = 0xFFAAAAAA;
    public static final int FAINT = 0xFF666670;
    public static final int BAD = 0xFFFF6B6B;

    private Draw() {}

    public static void round(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        if (x2 - x1 < 3 || y2 - y1 < 3) {
            g.fill(x1, y1, x2, y2, color);
            return;
        }
        g.fill(x1 + 1, y1, x2 - 1, y2, color);
        g.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
        g.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
    }

    public static void panel(GuiGraphics g, int x1, int y1, int x2, int y2, int fill, int border) {
        round(g, x1, y1, x2, y2, border);
        round(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, fill);
    }

    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void pill(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        if (y2 - y1 <= 4) {
            round(g, x1, y1, x2, y2, color);
            return;
        }
        g.fill(x1 + 2, y1, x2 - 2, y2, color);
        g.fill(x1 + 1, y1 + 1, x1 + 2, y2 - 1, color);
        g.fill(x2 - 2, y1 + 1, x2 - 1, y2 - 1, color);
        g.fill(x1, y1 + 2, x1 + 1, y2 - 2, color);
        g.fill(x2 - 1, y1 + 2, x2, y2 - 2, color);
    }

    /** Progress bar; {@code frac} is clamped to 0..1. */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, double frac, int color) {
        pill(g, x, y, x + w, y + h, TRACK);
        double f = Double.isNaN(frac) ? 0 : Math.max(0, Math.min(1, frac));
        int fw = (int) Math.round(w * f);
        if (fw >= 2) pill(g, x, y, x + fw, y + h, color);
    }

    public static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    /** Draws a whole square texture at size {@code size}. */
    public static void texture(GuiGraphics g, Identifier id, int x, int y, int size, int texSize) {
        RenderPipeline p = RenderPipelines.GUI_TEXTURED;
        g.blit(p, id, x, y, 0f, 0f, size, size, texSize, texSize, texSize, texSize);
    }

    /** Placeholder when there is no cover: a dark square with a note glyph. */
    public static void noCover(GuiGraphics g, Font font, int x, int y, int size) {
        round(g, x, y, x + size, y + size, 0xFF2A2C38);
        g.drawCenteredString(font, "♪", x + size / 2, y + size / 2 - 4, FAINT);
    }

    /** The small "E" box Spotify shows on explicit tracks. Returns the width it takes, including a gap. */
    public static int explicitBadge(GuiGraphics g, Font font, int x, int y) {
        round(g, x, y, x + 9, y + 9, 0xFF9A9AA6);
        g.drawString(font, "E", x + 2, y + 1, 0xFF121318, false);
        return 12;
    }

    /** Text cut to {@code width} with an ellipsis. */
    public static String ellipsize(Font font, String s, int width) {
        if (font.width(s) <= width) return s;
        return font.plainSubstrByWidth(s, Math.max(0, width - font.width("…"))) + "…";
    }

    /**
     * Text that does not fit scrolls sideways (character steps, with a pause at each end), so the HUD
     * never needs scissoring under a scaled pose.
     */
    public static String marquee(Font font, String s, int width, long now) {
        if (font.width(s) <= width) return s;
        String padded = s + "     ";
        int len = padded.length();
        long step = 220;
        long pause = 2000;
        long cycle = pause + len * step;
        long t = now % cycle;
        int start = t < pause ? 0 : (int) ((t - pause) / step);
        String rolled = (padded + padded).substring(start, start + len);
        return font.plainSubstrByWidth(rolled, width);
    }

    // ------------------------------------------------------------------ icons (drawn, so no font glyph gaps)

    public static void pause(GuiGraphics g, int cx, int cy, int h, int color) {
        int half = h / 2;
        g.fill(cx - 4, cy - half, cx - 1, cy + half + 1, color);
        g.fill(cx + 1, cy - half, cx + 4, cy + half + 1, color);
    }

    /** Triangle pointing right, widest at the left (the usual play shape). */
    public static void triangleRight(GuiGraphics g, int x, int cy, int h, int color) {
        int half = h / 2;
        for (int row = -half; row <= half; row++) {
            int w = (half - Math.abs(row)) + 1;
            g.fill(x, cy + row, x + w, cy + row + 1, color);
        }
    }

    public static void triangleLeft(GuiGraphics g, int x, int cy, int h, int color) {
        int half = h / 2;
        for (int row = -half; row <= half; row++) {
            int w = (half - Math.abs(row)) + 1;
            g.fill(x - w, cy + row, x, cy + row + 1, color);
        }
    }

    /** ▶| */
    public static void next(GuiGraphics g, int cx, int cy, int color) {
        triangleRight(g, cx - 4, cy, 8, color);
        g.fill(cx + 1, cy - 4, cx + 3, cy + 5, color);
    }

    /** |◀ */
    public static void prev(GuiGraphics g, int cx, int cy, int color) {
        g.fill(cx - 3, cy - 4, cx - 1, cy + 5, color);
        triangleLeft(g, cx + 4, cy, 8, color);
    }
}
