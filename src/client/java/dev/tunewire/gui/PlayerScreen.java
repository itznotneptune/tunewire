package dev.tunewire.gui;

import dev.tunewire.TunewireClient;
import dev.tunewire.art.CoverArt;
import dev.tunewire.config.TokenStore;
import dev.tunewire.config.TwConfig;
import dev.tunewire.core.Lrc;
import dev.tunewire.core.PlaybackState;
import dev.tunewire.core.Track;
import dev.tunewire.lyrics.LyricsService;
import dev.tunewire.spotify.SpotifyApi;
import dev.tunewire.spotify.SpotifyAuth;
import dev.tunewire.spotify.SpotifyPlayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The player: Now Playing (cover, seek, controls, volume, device, scrolling synced lyrics), Search
 * (play or queue tracks) and Settings (Spotify connection, HUD options).
 * Drawn immediate-mode: each frame records clickable rectangles, and clicks are matched against them.
 */
public final class PlayerScreen extends Screen {
    private enum Tab {
        PLAYER("Now Playing"), SEARCH("Search"), SETTINGS("Settings");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private enum Drag { NONE, SEEK, VOLUME }

    private static Tab lastTab = Tab.PLAYER;
    private static String searchText = "";
    private static String lastQuery = "";
    private static List<Track> results = List.of();

    private static final int HEADER = 26, FOOTER = 14;

    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> popupHits = new ArrayList<>();
    private Tab tab;
    private int px, py, pw, ph;

    private EditBox searchBox;
    private EditBox clientIdBox;
    private String clientIdText = TwConfig.get().clientId;
    private String searchStatus = "";
    private long searchDueAt;
    private int searchSeq;
    private double resultScroll;
    private int[] resultsRect;

    private double lyricScroll;
    private boolean lyricSnap = true;
    private long userScrolledAt;
    private String lyricsFor = "";
    private int[] lyricsRect;

    private boolean devicesOpen;
    private String devicesStatus = "";
    private int[] devicesAnchor = {0, 0};
    private int[] popupRect;

    private Drag drag = Drag.NONE;
    private double dragFrac;
    private int[] seekRect, volRect;

    private String note = "";
    private long noteAt;

    public PlayerScreen() {
        super(Component.literal("Tunewire"));
        tab = TokenStore.hasLogin() && !TwConfig.get().clientId.isEmpty() ? lastTab : Tab.SETTINGS;
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void init() {
        pw = Math.min(480, width - 16);
        ph = Math.min(280, height - 16);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        searchBox = null;
        clientIdBox = null;
        int x = px + 10, y = py + HEADER;
        if (tab == Tab.SEARCH) {
            searchBox = new EditBox(font, x + 6, y + 13, pw - 32, 12, Component.literal("Search"));
            searchBox.setBordered(false);
            searchBox.setMaxLength(120);
            searchBox.setTextColor(Draw.TEXT);
            searchBox.setValue(searchText);
            searchBox.setResponder(s -> {
                searchText = s;
                searchDueAt = System.currentTimeMillis() + 450;
            });
            addWidget(searchBox);
            setFocused(searchBox);
            searchBox.setFocused(true);
        } else if (tab == Tab.SETTINGS) {
            clientIdBox = new EditBox(font, x + 5, y + 44, settingsLeftW() - 70, 12, Component.literal("Client ID"));
            clientIdBox.setBordered(false);
            clientIdBox.setMaxLength(64);
            clientIdBox.setTextColor(Draw.TEXT);
            clientIdBox.setValue(clientIdText);
            clientIdBox.setResponder(s -> clientIdText = s);
            addWidget(clientIdBox);
        }
    }

    private int settingsLeftW() {
        return (pw - 20) / 2 + 10;
    }

    private void setTab(Tab t) {
        tab = t;
        lastTab = t;
        devicesOpen = false;
        rebuildWidgets();
    }

    private void note(String s) {
        note = s;
        noteAt = System.currentTimeMillis();
    }

    @Override
    public void tick() {
        if (searchDueAt > 0 && System.currentTimeMillis() >= searchDueAt) {
            searchDueAt = 0;
            runSearch(false);
        }
    }

    private void runSearch(boolean force) {
        String q = searchText.strip();
        if (q.isEmpty()) {
            results = List.of();
            lastQuery = "";
            searchStatus = "";
            return;
        }
        if (!force && q.equals(lastQuery)) return;
        lastQuery = q;
        int seq = ++searchSeq;
        searchStatus = "Searching…";
        SpotifyPlayer.INSTANCE.search(q).whenComplete((list, ex) -> minecraft.execute(() -> {
            if (seq != searchSeq) return;
            if (ex != null) {
                Throwable c = SpotifyApi.cause(ex);
                searchStatus = c instanceof SpotifyAuth.NotLoggedIn ? "Connect Spotify in Settings first." : "Search failed: " + c.getMessage();
                lastQuery = "";
                return;
            }
            results = list;
            resultScroll = 0;
            searchStatus = list.isEmpty() ? "No results for \"" + q + "\"" : "";
        }));
    }

    // ------------------------------------------------------------------ immediate-mode helpers

    private void hit(int x, int y, int w, int h, Runnable action) {
        hits.add(new Hit(x, y, w, h, action));
    }

    private boolean button(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String label, boolean primary, Runnable action) {
        boolean hover = Draw.in(mx, my, x, y, w, h);
        int bg = primary ? (hover ? 0xFF2BE36F : Draw.ACCENT) : (hover ? 0xFF34374A : 0xFF2A2C3A);
        Draw.round(g, x, y, x + w, y + h, bg);
        g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, primary ? 0xFF000000 : Draw.TEXT);
        hit(x, y, w, h, action);
        return hover;
    }

    private void toggle(GuiGraphics g, int mx, int my, int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
        boolean on = get.getAsBoolean();
        boolean hover = Draw.in(mx, my, x, y - 2, w, 14);
        g.drawString(font, label, x, y + 1, hover ? Draw.TEXT : Draw.MUTED, false);
        int sx = x + w - 20;
        Draw.pill(g, sx, y, sx + 20, y + 10, on ? Draw.ACCENT : Draw.TRACK);
        int kx = on ? sx + 11 : sx + 1;
        Draw.round(g, kx, y + 1, kx + 8, y + 9, Draw.TEXT);
        hit(x, y - 2, w, 14, () -> {
            set.accept(!on);
            TwConfig.save();
        });
    }

    private void stepper(GuiGraphics g, int mx, int my, int x, int y, int w, String label, String value, Runnable minus, Runnable plus) {
        g.drawString(font, label, x, y + 1, Draw.MUTED, false);
        int bx = x + w - 62;
        button(g, mx, my, bx, y - 1, 14, 12, "-", false, minus);
        g.drawCenteredString(font, value, bx + 31, y + 1, Draw.TEXT);
        button(g, mx, my, bx + 48, y - 1, 14, 12, "+", false, plus);
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        hits.clear();
        popupHits.clear();
        seekRect = volRect = lyricsRect = resultsRect = popupRect = null;
        int hmx = devicesOpen ? -1 : mx, hmy = devicesOpen ? -1 : my;

        Draw.panel(g, px, py, px + pw, py + ph, Draw.PANEL, Draw.BORDER);
        g.fill(px + 1, py + HEADER - 1, px + pw - 1, py + HEADER, Draw.BORDER);
        g.drawString(font, "♫", px + 10, py + 9, Draw.ACCENT, false);
        g.drawString(font, "Tunewire", px + 21, py + 9, Draw.TEXT, true);

        // tabs, right-aligned
        int tx = px + pw - 8;
        Tab[] tabs = Tab.values();
        for (int i = tabs.length - 1; i >= 0; i--) {
            Tab t = tabs[i];
            int w = font.width(t.label) + 14;
            tx -= w;
            boolean sel = t == tab;
            boolean hover = Draw.in(hmx, hmy, tx, py + 5, w, 16);
            if (sel) Draw.round(g, tx, py + 5, tx + w, py + 21, 0xFF262836);
            else if (hover) Draw.round(g, tx, py + 5, tx + w, py + 21, 0xFF1F2029);
            if (sel) g.fill(tx + 4, py + 19, tx + w - 4, py + 20, Draw.ACCENT);
            g.drawString(font, t.label, tx + 7, py + 9, sel || hover ? Draw.TEXT : Draw.MUTED, false);
            hit(tx, py + 5, w, 16, () -> setTab(t));
            tx -= 2;
        }

        int cx = px + 1, cy = py + HEADER, cw = pw - 2, ch = ph - HEADER - FOOTER;
        switch (tab) {
            case PLAYER -> renderPlayer(g, hmx, hmy, cx, cy, cw, ch);
            case SEARCH -> renderSearch(g, hmx, hmy, mx, my, delta, cx, cy, cw, ch);
            case SETTINGS -> renderSettings(g, hmx, hmy, mx, my, delta, cx, cy, cw, ch);
        }

        // footer: status or hints
        String st = SpotifyPlayer.INSTANCE.status(6000);
        boolean isNote = System.currentTimeMillis() - noteAt < 4000 && !note.isEmpty();
        String footer = isNote ? note : !st.isEmpty() ? st
                : tab == Tab.PLAYER ? "Space play/pause  •  ←/→ seek 10s  •  ↑/↓ volume  •  Esc close"
                : tab == Tab.SEARCH ? "Enter to search  •  click a song to play it, or + to queue it" : "Esc close";
        int fc = isNote ? Draw.ACCENT : !st.isEmpty() ? Draw.BAD : Draw.FAINT;
        g.drawString(font, Draw.ellipsize(font, footer, pw - 20), px + 10, py + ph - 11, fc, false);

        if (devicesOpen) renderDevices(g, mx, my);
    }

    // ---------------------------------------------------------------- now playing

    private void renderPlayer(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        SpotifyPlayer sp = SpotifyPlayer.INSTANCE;
        if (!TokenStore.hasLogin()) {
            g.drawCenteredString(font, "Not connected to Spotify.", x + w / 2, y + h / 2 - 20, Draw.TEXT);
            button(g, mx, my, x + w / 2 - 50, y + h / 2 - 4, 100, 16, "Open Settings", true, () -> setTab(Tab.SETTINGS));
            return;
        }
        PlaybackState s = sp.state();
        int leftW = Math.min(232, w / 2 + 6);
        int lx = x + 10, ly = y + 8;
        if (s == null || s.track() == null) {
            g.drawString(font, sp.everPolled() ? "Nothing is playing on Spotify." : "Talking to Spotify…", lx, ly + 4, Draw.TEXT, false);
            int yy = ly + 18;
            for (FormattedCharSequence line : font.split(Component.literal(
                    "Start a song in the Spotify app (PC or phone), choose a device, or use Search."), leftW - 20)) {
                g.drawString(font, line, lx, yy, Draw.MUTED, false);
                yy += 10;
            }
            button(g, mx, my, lx, yy + 6, 96, 16, "Choose device", true, this::toggleDevices);
            devicesAnchor = new int[] {lx, yy + 24};
            button(g, mx, my, lx + 102, yy + 6, 60, 16, "Search", false, () -> setTab(Tab.SEARCH));
            renderLyrics(g, mx, my, x + leftW, y + 6, w - leftW - 8, h - 10, null);
            return;
        }

        Track t = s.track();
        int cover = 96;
        Identifier id = CoverArt.INSTANCE.large();
        if (id != null) Draw.texture(g, id, lx, ly, cover, CoverArt.LARGE);
        else Draw.noCover(g, font, lx, ly, cover);

        int ix = lx + cover + 8, iw = leftW - cover - 20;
        int ty = ly + 2;
        List<FormattedCharSequence> titleLines = font.split(Component.literal(t.title()), iw);
        for (int i = 0; i < Math.min(3, titleLines.size()); i++) {
            g.drawString(font, titleLines.get(i), ix, ty, Draw.TEXT, true);
            ty += 10;
        }
        ty += 3;
        g.drawString(font, Draw.ellipsize(font, t.artistLine(), iw), ix, ty, Draw.MUTED, false);
        ty += 11;
        if (!t.album().isEmpty()) g.drawString(font, Draw.ellipsize(font, t.album(), iw), ix, ty, Draw.FAINT, false);

        String dev = (s.deviceName().isEmpty() ? "Unknown device" : s.deviceName()) + " ▾";
        int dy = ly + cover - 9;
        String devShown = Draw.ellipsize(font, dev, iw);
        boolean dh = Draw.in(mx, my, ix - 1, dy - 2, font.width(devShown) + 2, 12);
        g.drawString(font, devShown, ix, dy, dh || devicesOpen ? Draw.ACCENT : Draw.MUTED, false);
        hit(ix - 1, dy - 2, font.width(devShown) + 2, 12, this::toggleDevices);
        devicesAnchor = new int[] {ix, dy + 11};

        // seek bar
        int bw = leftW - 20;
        int by = ly + cover + 10;
        long dur = t.durationMs();
        long pos = drag == Drag.SEEK ? (long) (dragFrac * dur) : sp.position();
        seekRect = new int[] {lx, by - 3, bw, 10};
        boolean sh = drag == Drag.SEEK || Draw.in(mx, my, lx, by - 3, bw, 10);
        double frac = dur > 0 ? pos / (double) dur : 0;
        Draw.bar(g, lx, by, bw, 3, frac, sh ? Draw.ACCENT : Draw.TEXT);
        if (sh && dur > 0) {
            int kx = lx + (int) Math.round(frac * bw);
            Draw.round(g, kx - 3, by - 2, kx + 3, by + 5, Draw.TEXT);
        }
        g.drawString(font, PlaybackState.formatTime(pos), lx, by + 7, Draw.FAINT, false);
        String ds = PlaybackState.formatTime(dur);
        g.drawString(font, ds, lx + bw - font.width(ds), by + 7, Draw.FAINT, false);

        // transport controls
        int ccx = lx + bw / 2, ccy = by + 31;
        int r = 11;
        boolean phv = Draw.in(mx, my, ccx - r, ccy - r, r * 2, r * 2);
        Draw.pill(g, ccx - r, ccy - r, ccx + r, ccy + r, phv ? 0xFFFFFFFF : 0xFFE4E4E4);
        if (s.playing()) Draw.pause(g, ccx, ccy, 8, 0xFF000000);
        else Draw.triangleRight(g, ccx - 2, ccy, 10, 0xFF000000);
        hit(ccx - r, ccy - r, r * 2, r * 2, sp::togglePlay);

        iconButton(g, mx, my, ccx - 34, ccy, () -> sp.previous(), (c) -> Draw.prev(g, ccx - 34, ccy, c));
        iconButton(g, mx, my, ccx + 34, ccy, () -> sp.next(), (c) -> Draw.next(g, ccx + 34, ccy, c));
        glyphButton(g, mx, my, ccx - 66, ccy, "⇄", s.shuffle(), sp::toggleShuffle);
        glyphButton(g, mx, my, ccx + 66, ccy, "track".equals(s.repeat()) ? "↻1" : "↻", !"off".equals(s.repeat()), sp::cycleRepeat);

        // volume
        int vy = ccy + 22;
        if (s.volume() >= 0 && s.supportsVolume()) {
            g.drawString(font, "Vol", lx, vy, Draw.FAINT, false);
            int vx = lx + 22, vw = bw - 22 - 30;
            int vol = drag == Drag.VOLUME ? (int) Math.round(dragFrac * 100) : s.volume();
            volRect = new int[] {vx, vy - 1, vw, 10};
            boolean vh = drag == Drag.VOLUME || Draw.in(mx, my, vx, vy - 1, vw, 10);
            Draw.bar(g, vx, vy + 3, vw, 3, vol / 100.0, vh ? Draw.ACCENT : Draw.TEXT);
            if (vh) {
                int kx = vx + (int) Math.round(vol / 100.0 * vw);
                Draw.round(g, kx - 3, vy + 1, kx + 3, vy + 8, Draw.TEXT);
            }
            g.drawString(font, vol + "%", vx + vw + 6, vy, Draw.MUTED, false);
        } else {
            g.drawString(font, "This device doesn't allow volume control", lx, vy, Draw.FAINT, false);
        }

        renderLyrics(g, mx, my, x + leftW, y + 6, w - leftW - 8, h - 10, t);
    }

    private void iconButton(GuiGraphics g, int mx, int my, int cx, int cy, Runnable action, Consumer<Integer> icon) {
        boolean hover = Draw.in(mx, my, cx - 9, cy - 9, 18, 18);
        icon.accept(hover ? Draw.TEXT : 0xFFC8C8D0);
        hit(cx - 9, cy - 9, 18, 18, action);
    }

    private void glyphButton(GuiGraphics g, int mx, int my, int cx, int cy, String glyph, boolean active, Runnable action) {
        boolean hover = Draw.in(mx, my, cx - 9, cy - 9, 18, 18);
        int color = active ? Draw.ACCENT : hover ? Draw.TEXT : Draw.MUTED;
        g.drawCenteredString(font, glyph, cx, cy - 4, color);
        if (active) g.fill(cx - 1, cy + 6, cx + 1, cy + 8, Draw.ACCENT);
        hit(cx - 9, cy - 9, 18, 18, action);
    }

    // ---------------------------------------------------------------- lyrics panel

    private void renderLyrics(GuiGraphics g, int mx, int my, int x, int y, int w, int h, Track t) {
        Draw.round(g, x, y, x + w, y + h, 0xFF17181F);
        LyricsService.Lyrics ly = LyricsService.INSTANCE.current();
        int ax = x + 8, ay = y + 6, aw = w - 16, ah = h - 6 - 18;
        int fy = y + h - 14;

        if (!ly.trackUri().equals(lyricsFor)) {
            lyricsFor = ly.trackUri();
            lyricSnap = true;
            userScrolledAt = 0;
        }

        String message = switch (ly.status()) {
            case NONE -> t == null ? "Lyrics show here while a song plays." : "";
            case LOADING -> "Looking for lyrics…";
            case NOT_FOUND -> "No lyrics found for this song.";
            case INSTRUMENTAL -> "Instrumental ♪";
            case ERROR -> "Couldn't reach the lyrics service.";
            default -> null;
        };
        if (message != null) {
            g.drawCenteredString(font, message, x + w / 2, y + h / 2 - 4, Draw.FAINT);
            return;
        }
        lyricsRect = new int[] {ax, ay, aw, ah};
        TwConfig c = TwConfig.get();
        long now = System.currentTimeMillis();

        // lay out wrapped rows
        record Row(FormattedCharSequence text, int lineIdx, int y) {}
        List<Row> rows = new ArrayList<>();
        int yy = 0, curTop = -1, curBottom = -1;
        boolean synced = ly.status() == LyricsService.Status.SYNCED;
        List<Lrc.Line> lines = ly.lrc().lines();
        int current = synced ? ly.lrc().indexAt(SpotifyPlayer.INSTANCE.position() + c.lyricsOffsetMs) : -1;
        int count = synced ? lines.size() : ly.plain().size();
        for (int i = 0; i < count; i++) {
            String text = synced ? lines.get(i).text() : ly.plain().get(i);
            if (text.isEmpty()) text = synced ? "♪" : " ";
            if (i == current) curTop = yy;
            for (FormattedCharSequence part : font.split(Component.literal(text), aw)) {
                rows.add(new Row(part, i, yy));
                yy += 10;
            }
            if (i == current) curBottom = yy;
            yy += synced ? 4 : 1;
        }
        int total = yy;
        double max = Math.max(0, total - ah);
        if (synced && now - userScrolledAt > 4000) {
            double target = curTop < 0 ? 0 : (curTop + curBottom) / 2.0 - ah / 2.0;
            target = Math.max(0, Math.min(max, target));
            lyricScroll = lyricSnap ? target : lyricScroll + (target - lyricScroll) * 0.18;
            lyricSnap = false;
        }
        lyricScroll = Math.max(0, Math.min(max, lyricScroll));

        g.enableScissor(ax, ay, ax + aw, ay + ah);
        int hoverLine = -1;
        if (synced && Draw.in(mx, my, ax, ay, aw, ah)) {
            for (Row r : rows) {
                int ry = ay + r.y() - (int) lyricScroll;
                if (my >= ry - 2 && my < ry + 12) hoverLine = r.lineIdx();
            }
        }
        for (Row r : rows) {
            int ry = ay + r.y() - (int) lyricScroll;
            if (ry + 10 < ay || ry > ay + ah) continue;
            int color;
            if (!synced) color = 0xFFC8C8D0;
            else if (r.lineIdx() == current) color = Draw.TEXT;
            else if (r.lineIdx() == hoverLine) color = Draw.ACCENT;
            else if (r.lineIdx() < current) color = 0xFF6E6E78;
            else color = 0xFF9A9AA6;
            int tw = font.width(r.text());
            int rx = synced ? ax + (aw - tw) / 2 : ax;
            g.drawString(font, r.text(), rx, ry, color, synced && r.lineIdx() == current);
        }
        g.disableScissor();

        // click a synced line to jump there
        if (synced) {
            for (Row r : rows) {
                int ry = ay + r.y() - (int) lyricScroll;
                int top = Math.max(ay, ry - 2), bottom = Math.min(ay + ah, ry + 12);
                if (bottom <= top) continue;
                long time = lines.get(r.lineIdx()).timeMs();
                hit(ax, top, aw, bottom - top, () -> {
                    SpotifyPlayer.INSTANCE.seek(Math.max(0, time - TwConfig.get().lyricsOffsetMs));
                    userScrolledAt = 0;
                });
            }
        }

        // footer: source/version and offset
        g.fill(x + 4, fy - 3, x + w - 4, fy - 2, 0xFF262833);
        int vers = ly.versions().size();
        String src = (synced ? "Synced" : "Unsynced") + " · LRCLIB" + (vers > 1 ? " " + (ly.chosen() + 1) + "/" + vers : "");
        g.drawString(font, src, x + 8, fy + 1, Draw.FAINT, false);
        int fx = x + 8 + font.width(src) + 6;
        if (vers > 1) {
            button(g, mx, my, fx, fy - 1, font.width("Next version") + 8, 12, "Next version", false, LyricsService.INSTANCE::nextVersion);
        }
        if (synced) {
            String off = String.format("%+.1fs", c.lyricsOffsetMs / 1000.0);
            int ox = x + w - 8 - 14;
            button(g, mx, my, ox, fy - 1, 14, 12, "+", false, () -> shiftOffset(250));
            int ow = font.width(off);
            g.drawString(font, off, ox - 4 - ow, fy + 1, c.lyricsOffsetMs == 0 ? Draw.FAINT : Draw.TEXT, false);
            button(g, mx, my, ox - 8 - ow - 14, fy - 1, 14, 12, "-", false, () -> shiftOffset(-250));
        }
    }

    private void shiftOffset(int ms) {
        TwConfig c = TwConfig.get();
        c.lyricsOffsetMs = Math.max(-5000, Math.min(5000, c.lyricsOffsetMs + ms));
        TwConfig.save();
        note("Lyrics offset " + String.format("%+.2fs", c.lyricsOffsetMs / 1000.0) + " (positive shows lines earlier)");
    }

    // ---------------------------------------------------------------- devices popup

    private void toggleDevices() {
        devicesOpen = !devicesOpen;
        if (!devicesOpen) return;
        devicesStatus = "Loading devices…";
        SpotifyPlayer.INSTANCE.refreshDevices().whenComplete((list, ex) -> minecraft.execute(() -> {
            if (ex != null) devicesStatus = "Couldn't list devices: " + SpotifyApi.cause(ex).getMessage();
            else devicesStatus = list.isEmpty() ? "No devices. Open Spotify on this PC or your phone." : "";
        }));
    }

    private void renderDevices(GuiGraphics g, int mx, int my) {
        g.nextStratum();
        List<SpotifyPlayer.Device> list = devicesStatus.isEmpty() ? SpotifyPlayer.INSTANCE.devices() : List.of();
        int w = 190;
        int rows = Math.max(1, list.size());
        int h = rows * 16 + 8;
        int x = Math.min(devicesAnchor[0], px + pw - w - 6);
        int y = Math.min(devicesAnchor[1], py + ph - h - 6);
        popupRect = new int[] {x, y, w, h};
        Draw.panel(g, x, y, x + w, y + h, 0xFA16171D, Draw.ACCENT_DIM);
        if (list.isEmpty()) {
            g.drawString(font, Draw.ellipsize(font, devicesStatus, w - 12), x + 6, y + 8, Draw.MUTED, false);
            return;
        }
        int yy = y + 4;
        for (SpotifyPlayer.Device d : list) {
            boolean hover = Draw.in(mx, my, x + 2, yy, w - 4, 16);
            if (hover) Draw.round(g, x + 2, yy, x + w - 2, yy + 16, 0xFF262836);
            String label = d.name() + (d.type().isEmpty() ? "" : "  · " + d.type());
            g.drawString(font, Draw.ellipsize(font, label, w - 24), x + 8, yy + 4, d.active() ? Draw.ACCENT : Draw.TEXT, false);
            if (d.active()) g.drawString(font, "●", x + w - 14, yy + 4, Draw.ACCENT, false);
            SpotifyPlayer.Device dev = d;
            popupHits.add(new Hit(x + 2, yy, w - 4, 16, () -> {
                SpotifyPlayer.INSTANCE.transferTo(dev);
                devicesOpen = false;
            }));
            yy += 16;
        }
    }

    // ---------------------------------------------------------------- search

    private void renderSearch(GuiGraphics g, int hmx, int hmy, int mx, int my, float delta, int x, int y, int w, int h) {
        int fx = x + 9, fw = w - 18;
        boolean focused = searchBox != null && searchBox.isFocused();
        Draw.panel(g, fx, y + 8, fx + fw, y + 26, 0xFF0D0E12, focused ? Draw.ACCENT_DIM : Draw.BORDER);
        if (searchBox != null) {
            if (searchBox.getValue().isEmpty()) g.drawString(font, "Search songs or artists…", fx + 6, y + 13, Draw.FAINT, false);
            searchBox.render(g, mx, my, delta);
        }
        if (!TokenStore.hasLogin()) {
            g.drawCenteredString(font, "Connect Spotify in Settings to search.", x + w / 2, y + 60, Draw.MUTED);
            return;
        }
        int ay = y + 32, ah = h - 34, ax = fx, aw = fw;
        if (!searchStatus.isEmpty()) {
            g.drawCenteredString(font, searchStatus, x + w / 2, ay + 20, Draw.MUTED);
            if (results.isEmpty()) return;
            ay += 14;
            ah -= 14;
        } else if (results.isEmpty()) {
            g.drawCenteredString(font, "Type to search Spotify. Results play on your active device.", x + w / 2, ay + 20, Draw.FAINT);
            return;
        }
        resultsRect = new int[] {ax, ay, aw, ah};
        int rowH = 24;
        double max = Math.max(0, results.size() * rowH - ah);
        resultScroll = Math.max(0, Math.min(max, resultScroll));
        Track playing = SpotifyPlayer.INSTANCE.track();
        g.enableScissor(ax, ay, ax + aw, ay + ah);
        for (int i = 0; i < results.size(); i++) {
            int ry = ay + i * rowH - (int) resultScroll;
            if (ry + rowH < ay || ry > ay + ah) continue;
            Track t = results.get(i);
            boolean inArea = hmy >= ay && hmy < ay + ah;
            boolean hover = inArea && Draw.in(hmx, hmy, ax, ry, aw, rowH - 2);
            Draw.round(g, ax, ry, ax + aw, ry + rowH - 2, hover ? Draw.CARD_HOVER : Draw.CARD);
            boolean isPlaying = playing != null && playing.uri().equals(t.uri());
            int textW = aw - 16 - (hover ? 70 : 34);
            g.drawString(font, Draw.ellipsize(font, t.title(), textW), ax + 6, ry + 3, isPlaying ? Draw.ACCENT : Draw.TEXT, false);
            String sub = t.artistLine() + (t.album().isEmpty() ? "" : "  ·  " + t.album());
            g.drawString(font, Draw.ellipsize(font, sub, textW), ax + 6, ry + 13, Draw.MUTED, false);
            int top = Math.max(ay, ry), bottom = Math.min(ay + ah, ry + rowH - 2);
            if (bottom > top) hit(ax, top, aw, bottom - top, () -> SpotifyPlayer.INSTANCE.playTrack(t));
            if (hover) {
                int bx = ax + aw - 66;
                Draw.round(g, bx, ry + 5, bx + 30, ry + 17, Draw.ACCENT);
                Draw.triangleRight(g, bx + 12, ry + 11, 6, 0xFF000000);
                Draw.round(g, bx + 34, ry + 5, bx + 62, ry + 17, 0xFF34374A);
                g.drawCenteredString(font, "+Q", bx + 48, ry + 7, Draw.TEXT);
                if (bottom > top) {
                    hit(bx, Math.max(ay, ry + 5), 30, 12, () -> SpotifyPlayer.INSTANCE.playTrack(t));
                    hit(bx + 34, Math.max(ay, ry + 5), 28, 12, () -> SpotifyPlayer.INSTANCE.queue(t));
                }
            } else {
                String d = PlaybackState.formatTime(t.durationMs());
                g.drawString(font, d, ax + aw - 6 - font.width(d), ry + 8, Draw.FAINT, false);
            }
        }
        g.disableScissor();
    }

    // ---------------------------------------------------------------- settings

    private void renderSettings(GuiGraphics g, int mx, int my, int rmx, int rmy, float delta, int x, int y, int w, int h) {
        TwConfig c = TwConfig.get();
        int lx = x + 10, lw = settingsLeftW() - 18;
        int yy = y + 10;
        g.drawString(font, "SPOTIFY ACCOUNT", lx, yy, Draw.ACCENT, false);
        yy += 13;
        String state = SpotifyAuth.loginInProgress() ? "Waiting for you to approve in the browser…"
                : TokenStore.hasLogin() ? "Connected" : "Not connected";
        int stc = TokenStore.hasLogin() ? Draw.ACCENT : SpotifyAuth.loginInProgress() ? Draw.TEXT : Draw.MUTED;
        g.drawString(font, Draw.ellipsize(font, state, lw), lx, yy, stc, false);
        yy += 15;

        // client id field (box positioned in init at y + 44)
        int fw = lw - 52;
        boolean focused = clientIdBox != null && clientIdBox.isFocused();
        Draw.panel(g, lx, y + 39, lx + fw, y + 57, 0xFF0D0E12, focused ? Draw.ACCENT_DIM : Draw.BORDER);
        if (clientIdBox != null) {
            if (clientIdBox.getValue().isEmpty()) g.drawString(font, "Paste your app's Client ID", lx + 5, y + 44, Draw.FAINT, false);
            clientIdBox.render(g, rmx, rmy, delta);
        }
        button(g, mx, my, lx + fw + 4, y + 40, 48, 16, "Save", false, this::saveClientId);
        yy = y + 64;

        if (TokenStore.hasLogin()) {
            button(g, mx, my, lx, yy, 80, 16, "Reconnect", false, this::connect);
            button(g, mx, my, lx + 84, yy, 80, 16, "Disconnect", false, () -> {
                SpotifyAuth.logout();
                note("Disconnected from Spotify");
            });
        } else {
            button(g, mx, my, lx, yy, 120, 16, SpotifyAuth.loginInProgress() ? "Open browser again" : "Connect Spotify", true, this::connect);
        }
        yy += 24;

        String help = "One-time setup: 1) Open the Spotify dashboard and create an app (any name). "
                + "2) Add this Redirect URI: " + SpotifyAuth.REDIRECT_URI + " and tick \"Web API\". "
                + "3) Paste the app's Client ID above, Save, then Connect. Playback control needs Premium.";
        for (FormattedCharSequence line : font.split(Component.literal(help), lw)) {
            if (yy > y + h - 30) break;
            g.drawString(font, line, lx, yy, Draw.MUTED, false);
            yy += 10;
        }
        yy += 4;
        button(g, mx, my, lx, Math.min(yy, y + h - 20), 98, 16, "Open dashboard", false,
                () -> Util.getPlatform().openUri(SpotifyAuth.DASHBOARD));
        button(g, mx, my, lx + 102, Math.min(yy, y + h - 20), 110, 16, "Copy Redirect URI", false, () -> {
            minecraft.keyboardHandler.setClipboard(SpotifyAuth.REDIRECT_URI);
            note("Copied " + SpotifyAuth.REDIRECT_URI);
        });

        // right column
        int rx = x + settingsLeftW() + 4, rw = w - settingsLeftW() - 16;
        g.fill(rx - 8, y + 8, rx - 7, y + h - 8, Draw.BORDER);
        int ry = y + 10;
        g.drawString(font, "HUD", rx, ry, Draw.ACCENT, false);
        ry += 14;
        toggle(g, mx, my, rx, ry, rw, "Now playing card", () -> c.nowPlayingHud, v -> c.nowPlayingHud = v);
        ry += 15;
        toggle(g, mx, my, rx, ry, rw, "Cover art on the card", () -> c.hudCoverArt, v -> c.hudCoverArt = v);
        ry += 15;
        toggle(g, mx, my, rx, ry, rw, "Hide HUD while paused", () -> c.hideHudWhenPaused, v -> c.hideHudWhenPaused = v);
        ry += 15;
        button(g, mx, my, rx, ry, rw, 16, "Edit HUD layout", false, () -> minecraft.setScreen(new HudEditorScreen(this)));
        ry += 24;
        g.drawString(font, "LYRICS", rx, ry, Draw.ACCENT, false);
        ry += 14;
        toggle(g, mx, my, rx, ry, rw, "Lyrics on the HUD", () -> c.lyricsHud, v -> c.lyricsHud = v);
        ry += 15;
        toggle(g, mx, my, rx, ry, rw, "Show lines before and after", () -> c.lyricsLines == 3, v -> c.lyricsLines = v ? 3 : 1);
        ry += 15;
        toggle(g, mx, my, rx, ry, rw, "Shade behind current line", () -> c.lyricsBackground, v -> c.lyricsBackground = v);
        ry += 16;
        stepper(g, mx, my, rx, ry, rw, "Lyrics offset", String.format("%+.2fs", c.lyricsOffsetMs / 1000.0),
                () -> shiftOffset(-250), () -> shiftOffset(250));
        ry += 18;
        g.drawString(font, "CONTROLS", rx, ry, Draw.ACCENT, false);
        ry += 14;
        stepper(g, mx, my, rx, ry, rw, "Volume key step", c.volumeStep + "%", () -> {
            c.volumeStep = Math.max(1, c.volumeStep - (c.volumeStep > 5 ? 5 : 1));
            TwConfig.save();
        }, () -> {
            c.volumeStep = Math.min(25, c.volumeStep + (c.volumeStep >= 5 ? 5 : 1));
            TwConfig.save();
        });
        ry += 16;
        if (ry < y + h - 10) {
            g.drawString(font, Draw.ellipsize(font, "Hotkeys: Options → Controls → Tunewire", rw), rx, ry, Draw.FAINT, false);
        }
    }

    private void saveClientId() {
        String id = clientIdText.strip();
        TwConfig c = TwConfig.get();
        boolean changed = !id.equals(c.clientId);
        c.clientId = id;
        TwConfig.save();
        if (changed && TokenStore.hasLogin()) {
            SpotifyAuth.logout(); // tokens belong to the old app
            note("Client ID saved. Connect again for the new app.");
        } else {
            note(id.isEmpty() ? "Client ID cleared" : "Client ID saved");
        }
    }

    private void connect() {
        if (!clientIdText.strip().equals(TwConfig.get().clientId)) saveClientId();
        SpotifyAuth.startLogin();
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        double mx = e.x(), my = e.y();
        if (devicesOpen) {
            if (popupRect != null && Draw.in(mx, my, popupRect[0], popupRect[1], popupRect[2], popupRect[3])) {
                for (Hit h : popupHits) {
                    if (Draw.in(mx, my, h.x(), h.y(), h.w(), h.h())) {
                        h.action().run();
                        return true;
                    }
                }
                return true;
            }
            devicesOpen = false;
            return true;
        }
        if (super.mouseClicked(e, dbl)) return true; // edit boxes
        if (getFocused() instanceof EditBox box) {
            box.setFocused(false);
            setFocused(null);
        }
        if (e.button() != 0) return false;
        if (seekRect != null && Draw.in(mx, my, seekRect[0], seekRect[1], seekRect[2], seekRect[3])) {
            drag = Drag.SEEK;
            dragFrac = frac(mx, seekRect);
            return true;
        }
        if (volRect != null && Draw.in(mx, my, volRect[0], volRect[1], volRect[2], volRect[3])) {
            drag = Drag.VOLUME;
            dragFrac = frac(mx, volRect);
            SpotifyPlayer.INSTANCE.setVolume((int) Math.round(dragFrac * 100));
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) { // last drawn is on top
            Hit h = hits.get(i);
            if (Draw.in(mx, my, h.x(), h.y(), h.w(), h.h())) {
                h.action().run();
                return true;
            }
        }
        return false;
    }

    private static double frac(double mx, int[] r) {
        return Math.max(0, Math.min(1, (mx - r[0]) / (double) r[2]));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (drag == Drag.SEEK && seekRect != null) {
            dragFrac = frac(e.x(), seekRect);
            return true;
        }
        if (drag == Drag.VOLUME && volRect != null) {
            dragFrac = frac(e.x(), volRect);
            SpotifyPlayer.INSTANCE.setVolume((int) Math.round(dragFrac * 100));
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (drag == Drag.SEEK) {
            Track t = SpotifyPlayer.INSTANCE.track();
            if (t != null && t.durationMs() > 0) SpotifyPlayer.INSTANCE.seek((long) (dragFrac * t.durationMs()));
        }
        drag = Drag.NONE;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (lyricsRect != null && Draw.in(mx, my, lyricsRect[0], lyricsRect[1], lyricsRect[2], lyricsRect[3])) {
            lyricScroll -= sy * 24;
            userScrolledAt = System.currentTimeMillis();
            return true;
        }
        if (resultsRect != null && Draw.in(mx, my, resultsRect[0], resultsRect[1], resultsRect[2], resultsRect[3])) {
            resultScroll -= sy * 24;
            return true;
        }
        if (volRect != null && Draw.in(mx, my, volRect[0], volRect[1] - 4, volRect[2], volRect[3] + 8)) {
            PlaybackState s = SpotifyPlayer.INSTANCE.state();
            if (s != null && s.volume() >= 0) SpotifyPlayer.INSTANCE.setVolume(s.volume() + (sy > 0 ? 5 : -5));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (key == GLFW.GLFW_KEY_ESCAPE && devicesOpen) {
            devicesOpen = false;
            return true;
        }
        if (getFocused() instanceof EditBox box && box.isFocused()) {
            if (box == searchBox && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
                searchDueAt = 0;
                runSearch(true);
                return true;
            }
            if (box == clientIdBox && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
                saveClientId();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(e);
            return super.keyPressed(e) || true; // typing must not trigger hotkeys below
        }
        SpotifyPlayer sp = SpotifyPlayer.INSTANCE;
        boolean ctrl = (e.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (key == GLFW.GLFW_KEY_F && ctrl || key == GLFW.GLFW_KEY_SLASH) {
            setTab(Tab.SEARCH);
            return true;
        }
        if (tab != Tab.SETTINGS && TokenStore.hasLogin()) {
            switch (key) {
                case GLFW.GLFW_KEY_SPACE -> {
                    sp.togglePlay();
                    return true;
                }
                case GLFW.GLFW_KEY_LEFT -> {
                    sp.seekBy(-10_000);
                    return true;
                }
                case GLFW.GLFW_KEY_RIGHT -> {
                    sp.seekBy(10_000);
                    return true;
                }
                case GLFW.GLFW_KEY_UP -> {
                    PlaybackState s = sp.state();
                    if (s != null && s.volume() >= 0) sp.setVolume(s.volume() + TwConfig.get().volumeStep);
                    return true;
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    PlaybackState s = sp.state();
                    if (s != null && s.volume() >= 0) sp.setVolume(s.volume() - TwConfig.get().volumeStep);
                    return true;
                }
                default -> {
                }
            }
        }
        return super.keyPressed(e);
    }

    @Override
    public void removed() {
        TwConfig.save();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Opens the player from anywhere (keybind, command). */
    public static void open() {
        TunewireClient.openScreen(new PlayerScreen());
    }
}
