package dev.tunewire.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.tunewire.Perf;
import dev.tunewire.config.TokenStore;
import dev.tunewire.config.TwConfig;
import dev.tunewire.core.Censor;
import dev.tunewire.core.Lrc;
import dev.tunewire.core.PlaybackState;
import dev.tunewire.lyrics.LyricsService;
import dev.tunewire.spotify.SpotifyPlayer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Lyrics as 3D text in the world, in one of two styles:
 * <ul>
 *   <li><b>world</b>: a few blocks in front of the camera, trailing the view with a short ease, scrolling
 *       smoothly through {@link LyricsFlow}.</li>
 *   <li><b>placed</b>: each line is set down in the world where you are looking when it starts and stays
 *       there while you move; the old line fades out as the new one fades in.</li>
 * </ul>
 * Text always faces the camera, like a name tag.
 */
public final class WorldLyrics {
    /** Wrap width in font units; at the base scale that is about 5 blocks wide. */
    private static final int WRAP = 200;
    private static final float BASE_SCALE = 0.025f;
    private static final int LINE_H = 11;
    private static final LyricsFlow FLOW = new LyricsFlow(WRAP, LINE_H);
    private static final long FADE_IN_NS = 260_000_000L, FADE_OUT_NS = 320_000_000L;

    /** A lyric line set down at a fixed world position. */
    private record Placed(String key, List<FormattedCharSequence> rows, float[] widths, Vec3 pos, long born) {}

    private static Vec3 anchor;
    private static long lastNanos;
    private static Placed current, previous;
    private static long previousDied;
    /** Local clock until which sample lines show even with nothing playing, so adjustments are visible. */
    private static volatile long previewUntil;

    private WorldLyrics() {}

    /** Show the 3D lyrics (sample lines if needed) for a few seconds; called when a placement setting changes. */
    public static void preview() {
        previewUntil = System.currentTimeMillis() + 4000;
        current = null; // re-place at the new distance/offset right away
    }

    private static boolean previewing() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof dev.tunewire.gui.PlayerScreen p && p.adjustingWorldLyrics()) return true;
        return System.currentTimeMillis() < previewUntil;
    }

    public static void render(WorldRenderContext ctx) {
        long t0 = Perf.start();
        try {
            renderInner(ctx);
        } finally {
            Perf.end(Perf.Section.WORLD, t0);
        }
    }

    private static void renderInner(WorldRenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        TwConfig c = TwConfig.get();
        if (!c.lyricsHud || !c.lyricsIn3d() || mc.options.hideGui || mc.player == null || !TokenStore.hasLogin()) {
            reset();
            return;
        }
        boolean preview = previewing();
        PlaybackState s = SpotifyPlayer.INSTANCE.state();
        if (!preview && (s == null || s.track() == null || !s.playing())) {
            reset();
            return;
        }
        MultiBufferSource buffers = ctx.consumers();
        PoseStack pose = ctx.matrices();
        if (buffers == null || pose == null) return; // never take the frame down over lyrics

        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.position();
        Vec3 target = spot(cam, c);
        long now = System.nanoTime();
        Font font = mc.font;
        Font.DisplayMode mode = c.worldThroughWalls ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;

        if ("placed".equals(c.lyricsMode)) {
            renderPlaced(font, pose, buffers, mode, cam, camPos, target, now, preview, c);
        } else {
            renderFollowing(font, pose, buffers, mode, cam, camPos, target, now, preview, c);
        }
        if (buffers instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
    }

    private static void reset() {
        anchor = null;
        current = null;
        previous = null;
    }

    /** Where the text goes: {@code distance} ahead of the camera, shifted right/up in camera space. */
    private static Vec3 spot(Camera cam, TwConfig c) {
        Vector3fc f = cam.forwardVector();
        // the camera rotation maps +X to screen-right and +Y to screen-up
        Vector3f right = cam.rotation().transform(new Vector3f(1, 0, 0));
        Vector3f up = cam.rotation().transform(new Vector3f(0, 1, 0));
        float d = c.worldDistance, ox = c.worldOffsetX, oy = c.worldOffsetY;
        return cam.position().add(
                f.x() * d + right.x() * ox + up.x() * oy,
                f.y() * d + right.y() * ox + up.y() * oy,
                f.z() * d + right.z() * ox + up.z() * oy);
    }

    // ------------------------------------------------------------------ following the view

    private static void renderFollowing(Font font, PoseStack pose, MultiBufferSource buffers, Font.DisplayMode mode,
                                        Camera cam, Vec3 camPos, Vec3 target, long now, boolean preview, TwConfig c) {
        List<LyricsFlow.DrawRow> rows = FLOW.rows(font, preview);
        if (rows.isEmpty()) return;
        double dt = lastNanos == 0 ? 0 : Math.min(0.25, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (anchor == null || anchor.distanceToSqr(target) > 64) {
            anchor = target; // first frame, or teleported: no swoop across the map
        } else {
            double k = 1 - Math.exp(-dt * 7); // frame-rate independent ease, ~150 ms to settle
            anchor = anchor.add(target.subtract(anchor).scale(k));
        }
        Matrix4f m = billboard(pose, cam, camPos, anchor, c);
        for (LyricsFlow.DrawRow r : rows) {
            if (r.width() <= 0) continue;
            int rgb = LyricsFlow.mix(0xB8B8C0, 0xFFFFFF, r.emphasis());
            int color = LyricsFlow.withAlpha(rgb, r.alpha() * (0.7f + 0.3f * r.emphasis()));
            float shade = 0.4f * r.emphasis() * r.alpha(); // eased, so the backdrop cross-fades between lines
            int bg = c.lyricsBackground && shade > 0.03f ? LyricsFlow.withAlpha(0, shade) : 0;
            font.drawInBatch(r.text(), -r.width() / 2f, r.y(), color, false, m, buffers, mode, bg, LightTexture.FULL_BRIGHT);
        }
        pose.popPose();
    }

    // ------------------------------------------------------------------ placed in the world

    private static void renderPlaced(Font font, PoseStack pose, MultiBufferSource buffers, Font.DisplayMode mode,
                                     Camera cam, Vec3 camPos, Vec3 target, long now, boolean preview, TwConfig c) {
        String[] line = currentLine(preview, c); // {key, text} or null
        String key = line == null ? null : line[0];
        boolean changed = current == null ? key != null : !current.key().equals(key);
        // walked or teleported far away from the line: put it back in front of you
        boolean lost = current != null && current.pos().distanceToSqr(camPos) > 48 * 48;
        if (changed || lost) {
            if (current != null && !lost) {
                previous = current;
                previousDied = now;
            }
            current = key == null ? null : place(font, key, line[1], target, now);
        }
        if (previous != null && now - previousDied > FADE_OUT_NS) previous = null;

        if (previous != null) {
            float a = 1f - (now - previousDied) / (float) FADE_OUT_NS;
            drawPlaced(font, pose, buffers, mode, cam, camPos, previous, a, 0, c);
        }
        if (current != null) {
            float t = Math.min(1f, (now - current.born()) / (float) FADE_IN_NS);
            float ease = 1 - (1 - t) * (1 - t);
            drawPlaced(font, pose, buffers, mode, cam, camPos, current, ease, (1 - ease) * 6, c);
        }
    }

    /** The line being sung now as {key, text}, null when none (intro, instrumental break, no synced lyrics). */
    private static String[] currentLine(boolean preview, TwConfig c) {
        LyricsService.Lyrics ly = LyricsService.INSTANCE.current();
        if (ly.status() != LyricsService.Status.SYNCED) {
            return preview ? new String[] {"preview", "Lyrics appear here, set down where you look"} : null;
        }
        Lrc lrc = ly.lrc();
        int i = lrc.indexAt(SpotifyPlayer.INSTANCE.position() + c.lyricsOffsetMs);
        if (i < 0) return preview ? new String[] {"preview", "Lyrics appear here, set down where you look"} : null;
        String text = lrc.lines().get(i).text();
        if (text.isEmpty()) text = "♪";
        if (c.censorLyrics) text = Censor.clean(text);
        return new String[] {ly.trackUri() + "#" + ly.chosen() + "#" + i, text};
    }

    private static Placed place(Font font, String key, String text, Vec3 pos, long now) {
        List<FormattedCharSequence> rows = font.split(Component.literal(text), WRAP);
        float[] widths = new float[rows.size()];
        for (int i = 0; i < rows.size(); i++) widths[i] = font.width(rows.get(i));
        return new Placed(key, rows, widths, pos, now);
    }

    /** {@code rise} lifts the text a little (font units) while it fades in. */
    private static void drawPlaced(Font font, PoseStack pose, MultiBufferSource buffers, Font.DisplayMode mode,
                                   Camera cam, Vec3 camPos, Placed p, float alpha, float rise, TwConfig c) {
        if (alpha < 0.04f) return;
        Matrix4f m = billboard(pose, cam, camPos, p.pos(), c);
        float y = -p.rows().size() * LINE_H / 2f + rise;
        int color = LyricsFlow.withAlpha(0xFFFFFF, alpha);
        int bg = c.lyricsBackground ? LyricsFlow.withAlpha(0, 0.35f * alpha) : 0;
        for (int i = 0; i < p.rows().size(); i++) {
            font.drawInBatch(p.rows().get(i), -p.widths()[i] / 2f, y, color, true, m, buffers, mode, bg, LightTexture.FULL_BRIGHT);
            y += LINE_H;
        }
        pose.popPose();
    }

    /** Pushes a pose at {@code at}, turned to face the camera and scaled to font units. Caller pops it. */
    private static Matrix4f billboard(PoseStack pose, Camera cam, Vec3 camPos, Vec3 at, TwConfig c) {
        pose.pushPose();
        pose.translate(at.x - camPos.x, at.y - camPos.y, at.z - camPos.z);
        pose.mulPose(cam.rotation());
        float scale = BASE_SCALE * c.worldScale;
        pose.scale(scale, -scale, scale);
        return pose.last().pose();
    }
}
