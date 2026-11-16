package dev.tunewire.gui;

import dev.tunewire.config.TwConfig;
import dev.tunewire.config.TwConfig.HudPos;
import dev.tunewire.core.PlaybackState;
import dev.tunewire.core.Track;
import dev.tunewire.hud.TunewireHud;
import dev.tunewire.spotify.SpotifyPlayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Drag the HUD elements; scroll to resize, right-click to reset size, R to reset everything. */
public final class HudEditorScreen extends Screen {
    private enum El { NOW_PLAYING, LYRICS }

    private static final PlaybackState SAMPLE = new PlaybackState(
            new Track("", "spotify:track:sample", "Song Title", List.of("Artist Name"), "Album", 200_000, "", false),
            true, 80_000, "", "", 50, true, false, "off", Long.MAX_VALUE);

    private final Screen parent;
    private El dragging;
    private double grabX, grabY;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("Tunewire HUD Layout"));
        this.parent = parent;
    }

    private boolean enabled(El e) {
        TwConfig c = TwConfig.get();
        return e == El.NOW_PLAYING ? c.nowPlayingHud : c.lyricsHud && !c.lyricsIn3d();
    }

    private int[] bounds(El e) {
        if (e == El.LYRICS) return TunewireHud.lyricsBounds(width, height);
        HudPos p = TwConfig.get().nowPlayingPos;
        return new int[] {p.x, p.y, Math.round(TunewireHud.NP_W * p.scale), Math.round(TunewireHud.NP_H * p.scale)};
    }

    private HudPos pos(El e) {
        TwConfig c = TwConfig.get();
        return e == El.NOW_PLAYING ? c.nowPlayingPos : c.lyricsPos;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        // no blur: the point is to see the elements over the game
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x40000000);
        TwConfig c = TwConfig.get();
        for (El e : El.values()) {
            if (!enabled(e)) continue;
            if (e == El.NOW_PLAYING) {
                PlaybackState s = SpotifyPlayer.INSTANCE.state();
                boolean real = s != null && s.track() != null;
                TunewireHud.drawNowPlaying(g, font, c.nowPlayingPos, real ? s : SAMPLE,
                        real ? SpotifyPlayer.INSTANCE.position() : 80_000);
            } else {
                TunewireHud.drawLyrics(g, font, width, height, true);
            }
            int[] b = bounds(e);
            boolean hover = dragging == e || Draw.in(mx, my, b[0], b[1], b[2], b[3]);
            Draw.outline(g, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, hover ? Draw.ACCENT : 0x80FFFFFF);
            if (hover) {
                String tag = (e == El.NOW_PLAYING ? "Now Playing" : "Lyrics") + "  " + Math.round(pos(e).scale * 100) + "%";
                g.drawString(font, tag, b[0], Math.max(2, b[1] - 11), Draw.ACCENT, true);
            }
        }
        String help = "Drag to move  •  Scroll to resize  •  Right-click: reset size  •  R: reset all  •  Esc: back";
        int w = font.width(help) + 16;
        Draw.panel(g, (width - w) / 2, height - 26, (width + w) / 2, height - 8, 0xE0121318, Draw.BORDER);
        g.drawCenteredString(font, help, width / 2, height - 21, Draw.MUTED);
    }

    private El at(double mx, double my) {
        El[] all = El.values();
        for (int i = all.length - 1; i >= 0; i--) {
            if (!enabled(all[i])) continue;
            int[] b = bounds(all[i]);
            if (Draw.in(mx, my, b[0], b[1], b[2], b[3])) return all[i];
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        El el = at(e.x(), e.y());
        if (el == null) return false;
        if (e.button() == 1) {
            pos(el).scale = 1f;
            return true;
        }
        dragging = el;
        int[] b = bounds(el);
        grabX = e.x() - b[0];
        grabY = e.y() - b[1];
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging == null) return false;
        int[] b = bounds(dragging);
        int x = (int) Math.max(0, Math.min(width - b[2], e.x() - grabX));
        int y = (int) Math.max(0, Math.min(height - b[3], e.y() - grabY));
        HudPos p = pos(dragging);
        if (dragging == El.LYRICS) {
            p.x = x + b[2] / 2; // lyrics are stored by their centre
        } else {
            p.x = x;
        }
        p.y = y;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        dragging = null;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        El el = at(mx, my);
        if (el == null) return false;
        HudPos p = pos(el);
        p.scale = Math.max(0.5f, Math.min(2.5f, Math.round((p.scale + (float) sy * 0.1f) * 10) / 10f));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_R) {
            TwConfig c = TwConfig.get();
            c.nowPlayingPos = new HudPos(6, 6, 1f);
            c.lyricsPos = new HudPos(-1, -1, 1f);
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        TwConfig.save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
