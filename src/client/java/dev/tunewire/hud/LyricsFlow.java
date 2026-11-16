package dev.tunewire.hud;

import dev.tunewire.config.TwConfig;
import dev.tunewire.core.Lrc;
import dev.tunewire.lyrics.LyricsService;
import dev.tunewire.spotify.SpotifyPlayer;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Smoothly scrolling lyrics for the HUD and the floating world text. The whole song is wrapped once
 * (cached per track and version); each frame only an eased "scroll index" moves toward the line being
 * sung, and the rows around it are returned with positions and fade values.
 */
public final class LyricsFlow {
    /** One wrapped row to draw. {@code y} is the row's top relative to the focus point, in font units. */
    public record DrawRow(FormattedCharSequence text, float y, float width, float alpha, float emphasis, boolean current) {}

    private static final int GAP = 3;
    private static final List<String> SAMPLE = List.of("The line before",
            "Lyrics appear here, in time with the song, and long lines wrap onto the next row", "The line after");

    private final int wrapWidth;
    private final int lineH;

    private String key = "";
    private List<FormattedCharSequence>[] wrapped;
    private float[][] rowWidths;
    private float[] tops;
    private float[] centers;

    private float smooth;
    private String smoothKey = "";
    private long lastNanos;

    public LyricsFlow(int wrapWidth, int lineH) {
        this.wrapWidth = wrapWidth;
        this.lineH = lineH;
    }

    /**
     * Rows to draw this frame; empty when there are no synced lyrics (unless {@code preview}, which
     * returns sample lines for the HUD editor).
     */
    public List<DrawRow> rows(Font font, boolean preview) {
        TwConfig c = TwConfig.get();
        LyricsService.Lyrics ly = LyricsService.INSTANCE.current();
        boolean synced = ly.status() == LyricsService.Status.SYNCED;
        if (!synced && !preview) return List.of();

        List<String> texts;
        String k;
        float target;
        if (synced) {
            boolean censor = c.censorLyrics;
            k = ly.trackUri() + "#" + ly.chosen() + "#" + censor;
            if (!k.equals(key)) {
                texts = new ArrayList<>();
                for (Lrc.Line l : ly.lrc().lines()) {
                    String t = l.text().isEmpty() ? "♪" : l.text();
                    texts.add(censor ? dev.tunewire.core.Censor.clean(t) : t);
                }
                build(font, k, texts);
            }
            target = ly.lrc().indexAt(SpotifyPlayer.INSTANCE.position() + c.lyricsOffsetMs);
        } else {
            k = "preview";
            if (!k.equals(key)) build(font, k, SAMPLE);
            target = 1;
        }

        // ease toward the sung line; jumps (seek, skip, new song) snap instead of sliding through the song
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (!k.equals(smoothKey) || Math.abs(target - smooth) > 3) {
            smooth = target;
            smoothKey = k;
        } else {
            smooth += (float) ((target - smooth) * (1 - Math.exp(-dt * 13))); // settles in ~0.25 s
            if (Math.abs(target - smooth) < 0.001f) smooth = target;
        }

        int n = tops.length;
        if (n == 0) return List.of();
        boolean three = c.lyricsLines == 3;
        float half = three ? 1.0f : 0.0f; // fully visible this many lines either side of the focus
        float fade = 0.85f;               // then fading out over this many lines
        float focus = centerAt(smooth);
        int cur = Math.round(target);
        List<DrawRow> out = new ArrayList<>();
        int from = (int) Math.floor(smooth - half - fade - 1), to = (int) Math.ceil(smooth + half + fade + 1);
        for (int j = Math.max(0, from); j <= Math.min(n - 1, to); j++) {
            float d = Math.abs(j - smooth);
            float alpha = d <= half ? 1f : Math.max(0f, 1f - (d - half) / fade);
            if (alpha < 0.04f) continue;
            float emphasis = Math.max(0f, 1f - d);
            List<FormattedCharSequence> parts = wrapped[j];
            for (int r = 0; r < parts.size(); r++) {
                out.add(new DrawRow(parts.get(r), tops[j] + r * lineH - focus, rowWidths[j][r], alpha, emphasis, j == cur));
            }
        }
        return out;
    }

    /** Height in font units that shows the configured number of lines with room for one wrap. */
    public int nominalHeight() {
        return (TwConfig.get().lyricsLines == 3 ? 4 : 2) * lineH + 4;
    }

    @SuppressWarnings("unchecked")
    private void build(Font font, String k, List<String> texts) {
        int n = texts.size();
        wrapped = new List[n];
        rowWidths = new float[n][];
        tops = new float[n];
        centers = new float[n];
        float y = 0;
        for (int i = 0; i < n; i++) {
            List<FormattedCharSequence> parts = font.split(Component.literal(texts.get(i)), wrapWidth);
            if (parts.isEmpty()) parts = List.of(FormattedCharSequence.EMPTY);
            wrapped[i] = parts;
            rowWidths[i] = new float[parts.size()];
            for (int r = 0; r < parts.size(); r++) rowWidths[i][r] = font.width(parts.get(r));
            float h = parts.size() * lineH;
            tops[i] = y;
            centers[i] = y + h / 2f - 1;
            y += h + GAP;
        }
        key = k;
    }

    /** Vertical centre of a fractional line index; before the first line it extrapolates upward. */
    private float centerAt(float f) {
        int n = centers.length;
        if (f <= 0) return centers[0] + f * (lineH + GAP);
        if (f >= n - 1) return centers[n - 1];
        int a = (int) Math.floor(f);
        float t = f - a;
        return centers[a] + (centers[a + 1] - centers[a]) * t;
    }

    /** ARGB with the alpha scaled; very faint values are dropped by callers. */
    public static int withAlpha(int rgb, float alpha) {
        int a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    public static int mix(int from, int to, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((from >> 16) & 255) + (((to >> 16) & 255) - ((from >> 16) & 255)) * t);
        int g = (int) (((from >> 8) & 255) + (((to >> 8) & 255) - ((from >> 8) & 255)) * t);
        int b = (int) ((from & 255) + ((to & 255) - (from & 255)) * t);
        return (r << 16) | (g << 8) | b;
    }
}
