package dev.tunewire.hud;

import dev.tunewire.art.CoverArt;
import dev.tunewire.config.TokenStore;
import dev.tunewire.config.TwConfig;
import dev.tunewire.config.TwConfig.HudPos;
import dev.tunewire.core.Lrc;
import dev.tunewire.core.PlaybackState;
import dev.tunewire.core.Track;
import dev.tunewire.gui.Draw;
import dev.tunewire.gui.HudEditorScreen;
import dev.tunewire.gui.PlayerScreen;
import dev.tunewire.lyrics.LyricsService;
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
    /** Fixed width the lyrics element reserves, so dragging it in the editor doesn't jump with line length. */
    public static final int LYRICS_W = 260;

    private TunewireHud() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (mc.screen instanceof PlayerScreen || mc.screen instanceof HudEditorScreen) return;
        if (!TokenStore.hasLogin()) return;
        TwConfig c = TwConfig.get();
        PlaybackState s = SpotifyPlayer.INSTANCE.state();
        if (s == null || s.track() == null) return;
        if (c.hideHudWhenPaused && !s.playing()) return;
        if (c.nowPlayingHud) drawNowPlaying(g, mc.font, c.nowPlayingPos, s, SpotifyPlayer.INSTANCE.position());
        if (c.lyricsHud && s.playing()) drawLyrics(g, mc.font, g.guiWidth(), g.guiHeight(), lyricLines(false), false);
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
        String title = t.title();
        int titleW = textW - (s.playing() ? 0 : 10);
        g.drawString(font, Draw.marquee(font, title, titleW, now), tx, 6, Draw.TEXT, true);
        if (!s.playing()) Draw.pause(g, NP_W - 10, 9, 6, Draw.MUTED);
        g.drawString(font, Draw.marquee(font, t.artistLine(), textW, now + 700), tx, 17, Draw.MUTED, false);

        long dur = t.durationMs();
        double frac = dur > 0 ? positionMs / (double) dur : 0;
        Draw.bar(g, tx, 30, textW, 3, frac, s.playing() ? Draw.ACCENT : Draw.MUTED);
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------ lyrics

    /** A line to draw: text, and whether it is the line being sung. */
    public record LyricLine(String text, boolean current) {}

    /** The lines for the lyrics element; {@code preview} gives sample lines for the HUD editor. */
    public static List<LyricLine> lyricLines(boolean preview) {
        TwConfig c = TwConfig.get();
        LyricsService.Lyrics ly = LyricsService.INSTANCE.current();
        if (ly.status() != LyricsService.Status.SYNCED) {
            if (!preview) return List.of();
            return c.lyricsLines == 1 ? List.of(new LyricLine("Lyrics appear here, in time with the song", true))
                    : List.of(new LyricLine("The line before", false),
                            new LyricLine("Lyrics appear here, in time with the song", true),
                            new LyricLine("The line after", false));
        }
        Lrc lrc = ly.lrc();
        long pos = SpotifyPlayer.INSTANCE.position() + c.lyricsOffsetMs;
        int i = lrc.indexAt(pos);
        List<Lrc.Line> all = lrc.lines();
        if (c.lyricsLines == 1) {
            return i < 0 ? List.of() : List.of(new LyricLine(text(all.get(i)), true));
        }
        String prev = i - 1 >= 0 ? text(all.get(i - 1)) : "";
        String cur = i >= 0 ? text(all.get(i)) : "";
        String next = i + 1 < all.size() ? text(all.get(i + 1)) : "";
        if (i < 0 && !all.isEmpty()) next = text(all.get(0)); // intro: show what's coming
        return List.of(new LyricLine(prev, false), new LyricLine(cur, true), new LyricLine(next, false));
    }

    private static String text(Lrc.Line l) {
        return l.text().isEmpty() ? "♪" : l.text();
    }

    /** Resolved top-left of the lyrics element at the current GUI size. */
    public static int[] lyricsOrigin(int screenW, int screenH) {
        HudPos p = TwConfig.get().lyricsPos;
        int[] size = lyricsSize();
        int w = Math.round(size[0] * p.scale), h = Math.round(size[1] * p.scale);
        int cx = p.x < 0 ? screenW / 2 : p.x;
        int y = p.y < 0 ? screenH - 64 - h : p.y;
        return new int[] {cx - w / 2, y, w, h};
    }

    public static int[] lyricsSize() {
        int lines = TwConfig.get().lyricsLines;
        return new int[] {LYRICS_W, lines * 11 + 6};
    }

    public static void drawLyrics(GuiGraphics g, Font font, int screenW, int screenH, List<LyricLine> lines, boolean forceBox) {
        if (lines.isEmpty() && !forceBox) return;
        TwConfig c = TwConfig.get();
        int[] o = lyricsOrigin(screenW, screenH);
        HudPos p = c.lyricsPos;
        int[] size = lyricsSize();
        g.pose().pushMatrix();
        g.pose().translate(o[0], o[1]);
        g.pose().scale(p.scale, p.scale);
        int y = 3;
        for (LyricLine l : lines) {
            if (!l.text().isEmpty()) {
                String s = Draw.ellipsize(font, l.text(), size[0] - 8);
                int w = font.width(s);
                int x = (size[0] - w) / 2;
                if (c.lyricsBackground && l.current()) Draw.round(g, x - 4, y - 2, x + w + 4, y + 10, 0x90000000);
                g.drawString(font, s, x, y, l.current() ? Draw.TEXT : 0xFFB8B8C0, l.current());
            }
            y += 11;
        }
        g.pose().popMatrix();
    }
}
