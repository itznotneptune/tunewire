package dev.tunewire.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.tunewire.Perf;
import dev.tunewire.config.TokenStore;
import dev.tunewire.config.TwConfig;
import dev.tunewire.core.PlaybackState;
import dev.tunewire.spotify.SpotifyPlayer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;

import java.util.List;

/**
 * Lyrics as floating 3D text a few blocks in front of the camera, facing it like a name tag.
 * The anchor trails the view direction with a short ease so the text feels placed in the world
 * rather than glued to the screen; lines scroll smoothly through the same {@link LyricsFlow} as the HUD.
 */
public final class WorldLyrics {
    /** Wrap width in font units; at the base scale that is about 5 blocks wide. */
    private static final int WRAP = 200;
    private static final float BASE_SCALE = 0.025f;
    private static final LyricsFlow FLOW = new LyricsFlow(WRAP, 11);

    private static Vec3 anchor;
    private static long lastNanos;

    private WorldLyrics() {}

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
        if (!c.lyricsHud || !c.lyricsInWorld || mc.options.hideGui || mc.player == null || !TokenStore.hasLogin()) {
            anchor = null;
            return;
        }
        PlaybackState s = SpotifyPlayer.INSTANCE.state();
        if (s == null || s.track() == null || !s.playing()) {
            anchor = null;
            return;
        }
        Font font = mc.font;
        List<LyricsFlow.DrawRow> rows = FLOW.rows(font, false);
        if (rows.isEmpty()) return;

        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 camPos = cam.position();
        Vector3fc f = cam.forwardVector();
        Vec3 target = camPos.add(f.x() * c.worldDistance, f.y() * c.worldDistance, f.z() * c.worldDistance);

        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : Math.min(0.25, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (anchor == null || anchor.distanceToSqr(target) > 64) {
            anchor = target; // first frame, or teleported: no swoop across the map
        } else {
            double k = 1 - Math.exp(-dt * 7); // frame-rate independent ease, ~150 ms to settle
            anchor = anchor.add(target.subtract(anchor).scale(k));
        }

        MultiBufferSource buffers = ctx.consumers();
        PoseStack pose = ctx.matrices();
        if (buffers == null || pose == null) return; // never take the frame down over lyrics
        pose.pushPose();
        pose.translate(anchor.x - camPos.x, anchor.y - camPos.y, anchor.z - camPos.z);
        pose.mulPose(cam.rotation());
        float scale = BASE_SCALE * c.worldScale;
        pose.scale(scale, -scale, scale);

        Font.DisplayMode mode = c.worldThroughWalls ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
        Matrix4f m = pose.last().pose();
        for (LyricsFlow.DrawRow r : rows) {
            if (r.width() <= 0) continue;
            int rgb = LyricsFlow.mix(0xB8B8C0, 0xFFFFFF, r.emphasis());
            int color = LyricsFlow.withAlpha(rgb, r.alpha() * (0.7f + 0.3f * r.emphasis()));
            int bg = c.lyricsBackground && r.current() ? LyricsFlow.withAlpha(0, 0.4f * r.emphasis() * r.alpha()) : 0;
            font.drawInBatch(r.text(), -r.width() / 2f, r.y(), color, false, m, buffers, mode, bg, LightTexture.FULL_BRIGHT);
        }
        pose.popPose();
        if (buffers instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
    }
}
