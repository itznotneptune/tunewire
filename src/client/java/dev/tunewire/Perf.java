package dev.tunewire;

import dev.tunewire.gui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A tiny built-in profiler for the mod's own work on the render and client threads. Toggled with
 * {@code /tunewire perf}; numbers are averaged over one second so they are readable.
 */
public final class Perf {
    public enum Section { HUD, WORLD, SCREEN, TICK }

    private static final int N = Section.values().length;
    private static final long[] acc = new long[N];
    private static final long[] peak = new long[N];
    private static final double[] shownAvgMs = new double[N];
    private static final double[] shownPeakMs = new double[N];
    private static long frames;
    private static long windowStart = System.nanoTime();
    private static double shownFps;
    private static int requests;
    private static int shownRequests;

    public static volatile boolean overlay;

    private Perf() {}

    public static long start() {
        return System.nanoTime();
    }

    public static void end(Section s, long t0) {
        long d = System.nanoTime() - t0;
        acc[s.ordinal()] += d;
        if (d > peak[s.ordinal()]) peak[s.ordinal()] = d;
    }

    /** Counts a Spotify/LRCLIB/cover HTTP request (any thread). */
    public static synchronized void request() {
        requests++;
    }

    /** Called once per rendered frame (from the HUD callback, which runs every frame). */
    public static void frame() {
        frames++;
        long now = System.nanoTime();
        if (now - windowStart < 1_000_000_000L) return;
        double secs = (now - windowStart) / 1e9;
        for (int i = 0; i < N; i++) {
            shownAvgMs[i] = frames == 0 ? 0 : acc[i] / 1e6 / frames;
            shownPeakMs[i] = peak[i] / 1e6;
            acc[i] = 0;
            peak[i] = 0;
        }
        shownFps = frames / secs;
        synchronized (Perf.class) {
            shownRequests = (int) Math.round(requests * 60 / secs);
            requests = 0;
        }
        frames = 0;
        windowStart = now;
    }

    public static void draw(GuiGraphics g) {
        if (!overlay) return;
        Minecraft mc = Minecraft.getInstance();
        double total = 0;
        for (double v : shownAvgMs) total += v;
        double frameMs = shownFps > 0 ? 1000 / shownFps : 0;
        String[] lines = {
                "Tunewire perf (avg / peak per frame)",
                String.format("HUD     %.3f / %.3f ms", shownAvgMs[0], shownPeakMs[0]),
                String.format("World   %.3f / %.3f ms", shownAvgMs[1], shownPeakMs[1]),
                String.format("Screen  %.3f / %.3f ms", shownAvgMs[2], shownPeakMs[2]),
                String.format("Tick    %.3f / %.3f ms", shownAvgMs[3], shownPeakMs[3]),
                String.format("Total %.3f ms = %.2f%% of a %.1f ms frame (%.0f fps)", total,
                        frameMs > 0 ? total / frameMs * 100 : 0, frameMs, shownFps),
                "HTTP requests: " + shownRequests + "/min",
        };
        int w = 0;
        for (String l : lines) w = Math.max(w, mc.font.width(l));
        int x = g.guiWidth() - w - 10, y = 4;
        g.fill(x - 4, y - 2, x + w + 4, y + lines.length * 10 + 1, 0xC0000000);
        for (int i = 0; i < lines.length; i++) {
            g.drawString(mc.font, lines[i], x, y + i * 10, i == 0 ? Draw.ACCENT : Draw.TEXT, false);
        }
    }
}
