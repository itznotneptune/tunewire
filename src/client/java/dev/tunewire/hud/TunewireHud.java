package dev.tunewire.hud;

import dev.tunewire.Perf;
import dev.tunewire.art.CoverArt;
import dev.tunewire.config.TokenStore;
import dev.tunewire.config.TwConfig;
import dev.tunewire.config.TwConfig.HudPos;
import dev.tunewire.core.PlaybackState;
import dev.tunewire.core.Track;
import dev.tunewire.gui.Draw;
import dev.tunewire.gui.HudEditorScreen;
import dev.tunewire.gui.PlayerScreen;
import dev.tunewire.spotify.SpotifyPlayer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

import java.util.List;

/** The two HUD elements: the now-playing card and the synced lyrics. */
public final class TunewireHud {
    public static final int NP_W = 168;
    public static final int NP_H = 42;
    private static final int COVER = 32;
    /** "Next up" replaces the artist line this long before the song ends. */
    public static final long NEXT_UP_MS = 20_000;
    /** Width lyric lines wrap at; the element keeps this width so dragging it doesn't jump. */
    public static final int LYRICS_W = 280;
    private static final int LINE_H = 11;

    public static final LyricsFlow FLOW = new LyricsFlow(LYRICS_W - 8, LINE_H);

    private TunewireHud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Perf.frame();
        long t0 = Perf.start();
        try {
            renderInner(g);
        } finally {
            Perf.end(Perf.Section.HUD, t0);
        }
        Perf.draw(g);
    }

    private static void renderInner(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (mc.screen instanceof PlayerScreen || mc.screen instanceof HudEditorScreen) return;
        if (!TokenStore.hasLogin()) return;
        TwConfig c = TwConfig.get();
        PlaybackState s = SpotifyPlayer.INSTANCE.state();
        if (s == null || s.track() == null) return;
        if (c.hideHudWhenPaused && !s.playing()) return;
        if (c.nowPlayingHud) drawNowPlaying(g, mc.font, c.nowPlayingPos, s, SpotifyPlayer.INSTANCE.position());
        if (c.lyricsHud && !c.lyricsInWorld && s.playing()) {
            drawLyrics(g, mc.font, g.guiWidth(), g.guiHeight(), false);
        }
    }

    // ------------------------------------------------------------------ now playing

    public static void drawNowPlaying(GuiGraphics g, Font font, HudPos pos, PlaybackState s, long positionMs) {
        TwConfig c = TwConfig.get();
        Track t = s.track();
        long now = System.currentTimeMillis();
        g.pose().pushMatrix();
        g.pose().translate(pos.x, pos.y);
        g.pose().scale(pos.scale, pos.scale);

        Draw.panel(g, 0, 0, NP_W, NP_H, 0xC0101116, 0xA02E3040);
        int tx = 6;
        if (c.hudCoverArt) {
            Identifier cover = CoverArt.INSTANCE.small();
            if (cover != null) Draw.texture(g, cover, 5, 5, COVER, CoverArt.SMALL);
            else Draw.noCover(g, font, 5, 5, COVER);
            tx = 5 + COVER + 6;
        }
        int textW = NP_W - tx - 6;
        int titleW = textW - (s.playing() ? 0 : 10);
        g.drawString(font, Draw.marquee(font, t.title(), titleW, now), tx, 6, Draw.TEXT, true);
        if (!s.playing()) Draw.pause(g, NP_W - 10, 9, 6, Draw.MUTED);

        long dur = t.durationMs();
        long left = dur - positionMs;
        Track next = SpotifyPlayer.INSTANCE.nextUp();
        if (next != null && dur > 0 && left >= 0 && left <= NEXT_UP_MS) {
            String label = "Next up: ";
            int lw = font.width(label);
            g.drawString(font, label, tx, 17, Draw.ACCENT, false);
            String what = next.title() + (next.artistLine().isEmpty() ? "" : " – " + next.artistLine());
            g.drawString(font, Draw.marquee(font, what, textW - lw, now), tx + lw, 17, Draw.TEXT, false);
        } else {
            g.drawString(font, Draw.marquee(font, t.artistLine(), textW, now + 700), tx, 17, Draw.MUTED, false);
        }

        double frac = dur > 0 ? positionMs / (double) dur : 0;
        Draw.bar(g, tx, 30, textW, 3, frac, s.playing() ? Draw.ACCENT : Draw.MUTED);
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------ lyrics

    /** {x, y, w, h} of the lyrics element at the current GUI size. Fixed per setting, so it never jitters. */
    public static int[] lyricsBounds(int screenW, int screenH) {
        HudPos p = TwConfig.get().lyricsPos;
        int w = Math.round(LYRICS_W * p.scale), h = Math.round(FLOW.nominalHeight() * p.scale);
        int cx = p.x < 0 ? screenW / 2 : p.x;
        int y = p.y < 0 ? screenH - 60 - h : p.y; // default: just above the hotbar
        return new int[] {cx - w / 2, y, w, h};
    }

    /**
     * Draws the smoothly scrolling lyrics. {@code preview} (HUD editor) shows sample lines when the song
     * has no synced lyrics, so the element can still be placed.
     */
    public static void drawLyrics(GuiGraphics g, Font font, int screenW, int screenH, boolean preview) {
        List<LyricsFlow.DrawRow> rows = FLOW.rows(font, preview);
        if (rows.isEmpty()) return;
        TwConfig c = TwConfig.get();
        int[] b = lyricsBounds(screenW, screenH);
        HudPos p = c.lyricsPos;
        g.pose().pushMatrix();
        g.pose().translate(b[0], b[1]);
        g.pose().scale(p.scale, p.scale);
        float centerY = FLOW.nominalHeight() / 2f;
        for (LyricsFlow.DrawRow r : rows) {
            int y = Math.round(centerY + r.y());
            int w = Math.round(r.width());
            if (w <= 0) continue;
            int x = (LYRICS_W - w) / 2;
            if (c.lyricsBackground && r.current() && r.emphasis() > 0.05f) {
                g.fill(x - 4, y - 1, x + w + 4, y + 10, LyricsFlow.withAlpha(0x000000, 0.56f * r.emphasis() * r.alpha()));
            }
            int rgb = LyricsFlow.mix(0xB8B8C0, 0xFFFFFF, r.emphasis());
            g.drawString(font, r.text(), x, y, LyricsFlow.withAlpha(rgb, r.alpha()), r.emphasis() > 0.5f);
        }
        g.pose().popMatrix();
    }
}
