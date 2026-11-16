package dev.tunewire.gui;

import dev.tunewire.TunewireClient;
import dev.tunewire.art.CoverArt;
import dev.tunewire.config.TokenStore;
import dev.tunewire.config.TwConfig;
import dev.tunewire.core.Collection;
import dev.tunewire.core.Lrc;
import dev.tunewire.core.PlaybackState;
import dev.tunewire.core.Track;
import dev.tunewire.hud.WorldLyrics;
import dev.tunewire.lyrics.LyricsService;
import dev.tunewire.spotify.Library;
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
        PLAYER("Now Playing"), QUEUE("Queue"), LIBRARY("Library"), SEARCH("Search"), SETTINGS("Settings");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private enum Drag { NONE, SEEK, VOLUME, MOVE, RESIZE }

    private static final int DEFAULT_W = 480, DEFAULT_H = 280, MIN_W = 380, MIN_H = 236, MARGIN = 4, GRIP = 10;
    private double grabX, grabY;

    private static Tab lastTab = Tab.PLAYER;
    private static String searchText = "";
    private static String lastQuery = "";
    private static List<Track> results = List.of();
    private static List<Collection> collResults = List.of();
    /** null = songs. */
    private static Collection.Kind searchKind = null;
    private double libScroll;
    private int[] libRect;

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

    /** Window bounds from the saved size/position, clamped to the current screen. */
    private void layoutWindow() {
        TwConfig c = TwConfig.get();
        int maxW = Math.max(MIN_W, width - 2 * MARGIN), maxH = Math.max(MIN_H, height - 2 * MARGIN);
        if (c.playerFit) {
            pw = Math.min(maxW, width);
            ph = Math.min(maxH, height);
            px = (width - pw) / 2;
            py = (height - ph) / 2;
            return;
        }
        pw = clamp(c.playerW > 0 ? c.playerW : DEFAULT_W, Math.min(MIN_W, width), Math.min(maxW, width));
        ph = clamp(c.playerH > 0 ? c.playerH : DEFAULT_H, Math.min(MIN_H, height), Math.min(maxH, height));
        px = c.playerX < 0 ? (width - pw) / 2 : clamp(c.playerX, 0, Math.max(0, width - pw));
        py = c.playerY < 0 ? (height - ph) / 2 : clamp(c.playerY, 0, Math.max(0, height - ph));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Keeps the text boxes attached to the window while it moves or resizes. */
    private void positionWidgets() {
        int[] r = contentRect();
        if (searchBox != null) {
            searchBox.setX(r[0] + 15);
            searchBox.setY(r[1] + 13);
            searchBox.setWidth(r[2] - 30);
        }
        if (clientIdBox != null) {
            clientIdBox.setX(r[0] + 14);
            clientIdBox.setY(r[1] + 44);
            clientIdBox.setWidth(settingsLeftW(r[2]) - 70);
        }
    }

    private static final int BAR = 50;

    private int sidebarW() {
        return clamp(pw / 4, 120, 180);
    }

    /** {x, y, w, h} of the area the current view draws in, for either layout. */
    private int[] contentRect() {
        if (TwConfig.get().appLayout()) {
            int sb = sidebarW();
            return new int[] {px + sb + 1, py + HEADER, pw - sb - 2, ph - HEADER - BAR};
        }
        return new int[] {px + 1, py + HEADER, pw - 2, ph - HEADER - FOOTER};
    }

    private void toggleLayout() {
        TwConfig c = TwConfig.get();
        c.playerLayout = c.appLayout() ? "tabs" : "app";
        TwConfig.save();
        devicesOpen = false;
        positionWidgets();
    }

    private void toggleFit() {
        TwConfig c = TwConfig.get();
        c.playerFit = !c.playerFit;
        TwConfig.save();
        layoutWindow();
        positionWidgets();
    }

    @Override
    protected void init() {
        layoutWindow();
        searchBox = null;
        clientIdBox = null;
        if (tab == Tab.SEARCH) {
            searchBox = new EditBox(font, 0, 0, 100, 12, Component.literal("Search")); // placed by positionWidgets()
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
            clientIdBox = new EditBox(font, 0, 0, 100, 12, Component.literal("Client ID"));
            clientIdBox.setBordered(false);
            clientIdBox.setMaxLength(64);
            clientIdBox.setTextColor(Draw.TEXT);
            clientIdBox.setValue(clientIdText);
            clientIdBox.setResponder(s -> clientIdText = s);
            addWidget(clientIdBox);
        }
        positionWidgets();
    }

    private record PanelRow(FormattedCharSequence text, int lineIdx, int y, int width) {}

    private String panelKey = "";
    private List<PanelRow> panelRows = List.of();
    private int panelTotal;
    private long lastPanelNanos;

    private double rowScale = 1.0;
    private double settingsScroll;
    private int[] settingsRect;
    private double queueScroll;
    private int[] queueRect;

    private int sp(int n) {
        return (int) Math.round(n * rowScale);
    }

    /** Width of the Settings tab's left (account) column for a content area {@code w} wide. */
    private int settingsLeftW(int w) {
        return (w - 18) / 2 + 10;
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
            collResults = List.of();
            lastQuery = "";
            searchStatus = "";
            return;
        }
        if (!force && q.equals(lastQuery)) return;
        lastQuery = q;
        int seq = ++searchSeq;
        searchStatus = "Searching…";
        Collection.Kind kind = searchKind;
        java.util.concurrent.CompletableFuture<? extends List<?>> f = kind == null
                ? SpotifyPlayer.INSTANCE.search(q) : Library.INSTANCE.search(q, kind);
        f.whenComplete((list, ex) -> minecraft.execute(() -> {
            if (seq != searchSeq) return;
            if (ex != null) {
                Throwable c = SpotifyApi.cause(ex);
                searchStatus = c instanceof SpotifyAuth.NotLoggedIn ? "Connect Spotify in Settings first." : "Search failed: " + c.getMessage();
                lastQuery = "";
                return;
            }
            if (kind == null) results = castList(list);
            else collResults = castList(list);
            resultScroll = 0;
            searchStatus = list.isEmpty() ? "No results for \"" + q + "\"" : "";
        }));
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(List<?> l) {
        return (List<T>) l;
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
        Draw.round(g, kx, y + 1, kx + 8, y + 9, Draw.FILL_WHITE);
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
        long t0 = dev.tunewire.Perf.start();
        try {
            renderInner(g, mx, my, delta);
        } finally {
            dev.tunewire.Perf.end(dev.tunewire.Perf.Section.SCREEN, t0);
        }
    }

    private void renderInner(GuiGraphics g, int mx, int my, float delta) {
        hits.clear();
        popupHits.clear();
        seekRect = volRect = lyricsRect = resultsRect = popupRect = settingsRect = queueRect = libRect = sideRect = null;
        layoutWindow(); // move/resize drags write the config, so this follows them too
        positionWidgets();
        int hmx = devicesOpen ? -1 : mx, hmy = devicesOpen ? -1 : my;

        Draw.panel(g, px, py, px + pw, py + ph, Draw.PANEL, Draw.BORDER);
        g.fill(px + 1, py + HEADER - 1, px + pw - 1, py + HEADER, Draw.BORDER);
        g.drawString(font, "♫", px + 10, py + 9, Draw.ACCENT, false);
        boolean app = TwConfig.get().appLayout();
        int tabsW = 0;
        if (!app) for (Tab t : Tab.values()) tabsW += font.width(t.label) + 12;
        if (px + 21 + font.width("Tunewire") + 8 < px + pw - 8 - tabsW - 40) { // hide the name when the window is narrow
            g.drawString(font, "Tunewire", px + 21, py + 9, Draw.TEXT, true);
        }

        // tabs, right-aligned (tabs layout only; the app layout navigates from its sidebar)
        int tx = px + pw - 8;
        Tab[] tabs = app ? new Tab[0] : Tab.values();
        for (int i = tabs.length - 1; i >= 0; i--) {
            Tab t = tabs[i];
            int w = font.width(t.label) + 10;
            tx -= w;
            boolean sel = t == tab;
            boolean hover = Draw.in(hmx, hmy, tx, py + 5, w, 16);
            if (sel) Draw.round(g, tx, py + 5, tx + w, py + 21, 0xFF262836);
            else if (hover) Draw.round(g, tx, py + 5, tx + w, py + 21, 0xFF1F2029);
            if (sel) g.fill(tx + 4, py + 19, tx + w - 4, py + 20, Draw.ACCENT);
            g.drawString(font, t.label, tx + 5, py + 9, sel || hover ? Draw.TEXT : Draw.MUTED, false);
            hit(tx, py + 5, w, 16, () -> setTab(t));
            tx -= 2;
        }

        // fit-to-screen / restore button, left of the tabs
        boolean fit = TwConfig.get().playerFit;
        int fbx = tx - 20;
        boolean fh = Draw.in(hmx, hmy, fbx, py + 5, 16, 16);
        if (fh) Draw.round(g, fbx, py + 5, fbx + 16, py + 21, 0xFF262836);
        int ic = fh ? Draw.TEXT : Draw.MUTED;
        if (fit) { // restore: two overlapping windows
            Draw.outline(g, fbx + 6, py + 8, 7, 7, ic);
            g.fill(fbx + 3, py + 11, fbx + 10, py + 18, fh ? 0xFF262836 : Draw.PANEL);
            Draw.outline(g, fbx + 3, py + 11, 7, 7, ic);
        } else { // fit: one window with a thick title edge
            Draw.outline(g, fbx + 3, py + 8, 10, 10, ic);
            g.fill(fbx + 3, py + 9, fbx + 13, py + 10, ic);
        }
        hit(fbx, py + 5, 16, 16, this::toggleFit);

        // layout switch: tabs ⇄ app-style (sidebar + playback bar)
        int lbx = fbx - 18;
        boolean lh = Draw.in(hmx, hmy, lbx, py + 5, 16, 16);
        if (lh) Draw.round(g, lbx, py + 5, lbx + 16, py + 21, 0xFF262836);
        int lc = lh ? Draw.TEXT : app ? Draw.ACCENT : Draw.MUTED;
        Draw.outline(g, lbx + 3, py + 8, 11, 10, lc);
        g.fill(lbx + 6, py + 9, lbx + 7, py + 17, lc);   // sidebar divider
        g.fill(lbx + 7, py + 14, lbx + 13, py + 15, lc); // playback bar
        hit(lbx, py + 5, 16, 16, this::toggleLayout);

        String st = SpotifyPlayer.INSTANCE.status(6000);
        boolean isNote = System.currentTimeMillis() - noteAt < 4000 && !note.isEmpty();

        int[] r = contentRect();
        int cx = r[0], cy = r[1], cw = r[2], ch = r[3];
        if (app) {
            renderSidebar(g, hmx, hmy, px + 1, py + HEADER, sidebarW(), ph - HEADER - BAR);
            renderBar(g, hmx, hmy, px + 1, py + ph - BAR, pw - 2, BAR);
            // status sits in the header in this layout (the bar replaces the footer)
            String headStatus = isNote ? note : st;
            if (!headStatus.isEmpty()) {
                int right = lbx - 8, left = px + 21 + font.width("Tunewire") + 12;
                String shown = Draw.ellipsize(font, headStatus, Math.max(0, right - left));
                g.drawString(font, shown, right - font.width(shown), py + 9, isNote ? Draw.ACCENT : Draw.BAD, false);
            }
        }
        switch (tab) {
            case PLAYER -> {
                if (app) renderAppNowPlaying(g, hmx, hmy, cx, cy, cw, ch);
                else renderPlayer(g, hmx, hmy, cx, cy, cw, ch);
            }
            case QUEUE -> renderQueue(g, hmx, hmy, cx, cy, cw, ch);
            case LIBRARY -> renderLibrary(g, hmx, hmy, cx, cy, cw, ch);
            case SEARCH -> renderSearch(g, hmx, hmy, mx, my, delta, cx, cy, cw, ch);
            case SETTINGS -> renderSettings(g, hmx, hmy, mx, my, delta, cx, cy, cw, ch);
        }

        // header button tooltips, drawn last so content never covers them
        String tip = fh ? (fit ? "Restore window size" : "Fit to screen")
                : lh ? (app ? "Switch to the tabs layout" : "Switch to the app layout (sidebar + playback bar)") : null;
        if (tip != null) {
            int tw = font.width(tip);
            int tipX = Math.max(px + 4, Math.min(px + pw - tw - 4, (lh ? lbx : fbx) + 8 - tw / 2));
            g.nextStratum();
            g.fill(tipX - 3, py + HEADER + 1, tipX + tw + 3, py + HEADER + 13, 0xE0101116);
            g.drawString(font, tip, tipX, py + HEADER + 3, Draw.MUTED, false);
        }

        if (app) {
            renderGrip(g, hmx, hmy);
            if (devicesOpen) renderDevices(g, mx, my);
            return;
        }

        // footer: status or hints
        String footer = isNote ? note : !st.isEmpty() ? st
                : tab == Tab.PLAYER ? "Space play/pause  •  ←/→ seek 10s  •  ↑/↓ volume  •  Esc close"
                : tab == Tab.QUEUE ? "Add songs with +Q in Search  •  includes what Spotify plays next from the album or playlist"
                : tab == Tab.LIBRARY ? "Click a playlist or album to open it  •  ▶ plays it as a whole"
                : tab == Tab.SEARCH ? "Enter to search  •  click a song to play it, or + to queue it" : "Esc close";
        int fc = isNote ? Draw.ACCENT : !st.isEmpty() ? Draw.BAD : Draw.FAINT;
        g.drawString(font, Draw.ellipsize(font, footer, pw - 34), px + 10, py + ph - 11, fc, false);

        renderGrip(g, hmx, hmy);
        if (devicesOpen) renderDevices(g, mx, my);
    }

    /** Resize grip, bottom-right. */
    private void renderGrip(GuiGraphics g, int hmx, int hmy) {
        if (TwConfig.get().playerFit) return;
        boolean gh = drag == Drag.RESIZE || Draw.in(hmx, hmy, px + pw - GRIP, py + ph - GRIP, GRIP, GRIP);
        int gc = gh ? Draw.ACCENT : Draw.FAINT;
        int gx = px + pw - 4, gy = py + ph - 4;
        for (int a = 0; a < 3; a++) {
            for (int b = 0; a + b < 3; b++) {
                g.fill(gx - a * 3, gy - b * 3, gx - a * 3 + 1, gy - b * 3 + 1, gc); // triangle of dots in the corner
            }
        }
    }

    // ---------------------------------------------------------------- app layout: sidebar, playback bar

    private double sideScroll;
    private int[] sideRect;

    private void renderSidebar(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, Draw.SIDEBAR);
        g.fill(x + w, y, x + w + 1, y + h, Draw.BORDER);
        int yy = y + 6;
        yy = navItem(g, mx, my, x, yy, w, "♫", "Now Playing", Tab.PLAYER);
        yy = navItem(g, mx, my, x, yy, w, "⌕", "Search", Tab.SEARCH);
        yy = navItem(g, mx, my, x, yy, w, "≡", "Queue", Tab.QUEUE);
        g.fill(x + 8, yy + 3, x + w - 8, yy + 4, Draw.BORDER);
        yy += 9;
        g.drawString(font, "YOUR LIBRARY", x + 9, yy, Draw.FAINT, false);
        int settingsY = y + h - 20;
        navItem(g, mx, my, x, settingsY, w, "⚙", "Settings", Tab.SETTINGS);
        int ay = yy + 12, ah = settingsY - 4 - ay;
        if (ah < 16) return;

        if (!TokenStore.hasLogin()) {
            g.drawString(font, Draw.ellipsize(font, "Not connected", w - 16), x + 9, ay + 2, Draw.MUTED, false);
            return;
        }
        if (!TokenStore.hasLibraryScope()) {
            List<FormattedCharSequence> lines = font.split(Component.literal("Reconnect to show your playlists here."), w - 16);
            for (FormattedCharSequence l : lines) {
                g.drawString(font, l, x + 9, ay + 2, Draw.MUTED, false);
                ay += 10;
            }
            button(g, mx, my, x + 8, ay + 4, w - 16, 14, "Reconnect", true, this::connect);
            return;
        }
        Library lib = Library.INSTANCE;
        lib.ensureRoot();
        List<Collection> entries = new ArrayList<>();
        entries.add(Collection.liked(lib.likedTotal()));
        entries.addAll(lib.playlists().items());
        entries.addAll(lib.albums().items());
        int rowH = 16;
        sideRect = new int[] {x, ay, w, ah};
        double max = Math.max(0, entries.size() * rowH - ah);
        sideScroll = Math.max(0, Math.min(max, sideScroll));
        Collection open = tab == Tab.LIBRARY ? lib.open() : null;
        g.enableScissor(x, ay, x + w, ay + ah);
        for (int i = 0; i < entries.size(); i++) {
            int ry = ay + i * rowH - (int) sideScroll;
            if (ry + rowH < ay || ry > ay + ah) continue;
            Collection c = entries.get(i);
            boolean sel = open != null && open.kind() == c.kind() && open.uri().equals(c.uri());
            boolean hover = my >= ay && my < ay + ah && Draw.in(mx, my, x + 4, ry, w - 8, rowH);
            if (sel) Draw.round(g, x + 4, ry, x + w - 4, ry + rowH, 0xFF262836);
            else if (hover) Draw.round(g, x + 4, ry, x + w - 4, ry + rowH, 0xFF1F2029);
            String icon = c.kind() == Collection.Kind.LIKED ? "♥" : c.kind() == Collection.Kind.ALBUM ? "◉" : "≡";
            g.drawString(font, icon, x + 9, ry + 4, c.kind() == Collection.Kind.LIKED || sel ? Draw.ACCENT : Draw.FAINT, false);
            g.drawString(font, Draw.ellipsize(font, c.name(), w - 30), x + 20, ry + 4, sel || hover ? Draw.TEXT : Draw.MUTED, false);
            int top = Math.max(ay, ry), bottom = Math.min(ay + ah, ry + rowH);
            if (bottom > top) hit(x + 4, top, w - 8, bottom - top, () -> {
                Library.INSTANCE.openCollection(c);
                libScroll = 0;
                if (tab != Tab.LIBRARY) setTab(Tab.LIBRARY);
            });
        }
        g.disableScissor();
        // next page of playlists/albums as the end of the list scrolls into view
        if (entries.size() * rowH - sideScroll < ah + 40) {
            if (lib.playlists().hasMore()) lib.morePlaylists();
            else if (lib.albums().hasMore()) lib.moreAlbums();
        }
    }

    private int navItem(GuiGraphics g, int mx, int my, int x, int y, int w, String icon, String label, Tab t) {
        boolean sel = tab == t;
        boolean hover = Draw.in(mx, my, x + 4, y, w - 8, 16);
        if (sel) Draw.round(g, x + 4, y, x + w - 4, y + 16, 0xFF262836);
        else if (hover) Draw.round(g, x + 4, y, x + w - 4, y + 16, 0xFF1F2029);
        g.drawString(font, icon, x + 9, y + 4, sel ? Draw.ACCENT : Draw.MUTED, false);
        g.drawString(font, label, x + 21, y + 4, sel || hover ? Draw.TEXT : Draw.MUTED, false);
        hit(x + 4, y, w - 8, 16, () -> {
            if (t == Tab.LIBRARY) Library.INSTANCE.close();
            if (tab != t) setTab(t);
        });
        return y + 17;
    }

    /** The bottom playback bar: track on the left, transport and seek in the middle, device and volume on the right. */
    private void renderBar(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, Draw.BORDER);
        g.fill(x, y + 1, x + w, y + h - 1, 0xF00E0F13);
        SpotifyPlayer sp = SpotifyPlayer.INSTANCE;
        PlaybackState s = sp.state();
        Track t = s == null ? null : s.track();
        int leftW = clamp(w * 3 / 10, 110, 240);
        int sw = clamp(w - 2 * leftW - 120, 100, 380); // seek bar width, centred
        int seekRight = x + w / 2 + sw / 2 + 36;      // its right edge, including the length label

        // left: what's playing
        int cover = 34;
        if (t != null) {
            Identifier id = CoverArt.INSTANCE.small();
            if (id != null) Draw.texture(g, id, x + 8, y + 8, cover, CoverArt.SMALL);
            else Draw.noCover(g, font, x + 8, y + 8, cover);
            int tx = x + 8 + cover + 8, tw = leftW - cover - 20;
            boolean th = Draw.in(mx, my, tx, y + 10, tw, 22);
            int bw = t.explicit() ? Draw.explicitBadge(g, font, tx, y + 12) : 0;
            g.drawString(font, Draw.ellipsize(font, t.title(), tw - bw), tx + bw, y + 13, th ? Draw.ACCENT : Draw.TEXT, false);
            g.drawString(font, Draw.ellipsize(font, t.artistLine(), tw), tx, y + 25, Draw.MUTED, false);
            hit(tx, y + 10, tw, 22, () -> {
                if (tab != Tab.PLAYER) setTab(Tab.PLAYER);
            });
        } else {
            g.drawString(font, TokenStore.hasLogin() ? "Nothing playing" : "Not connected", x + 10, y + 20, Draw.MUTED, false);
        }

        // right: device and volume
        int vw = clamp(w / 9, 44, 90);
        int vx = x + w - 14 - vw - 26;
        int vy = y + 21;
        if (s != null && s.volume() >= 0 && s.supportsVolume()) {
            int vol = drag == Drag.VOLUME ? (int) Math.round(dragFrac * 100) : s.volume();
            volRect = new int[] {vx, vy - 3, vw, 10};
            boolean vh = drag == Drag.VOLUME || Draw.in(mx, my, vx, vy - 3, vw, 10);
            Draw.bar(g, vx, vy, vw, 3, vol / 100.0, vh ? 0xFF4BEF86 : Draw.ACCENT);
            if (vh) {
                int kx = vx + (int) Math.round(vol / 100.0 * vw);
                Draw.round(g, kx - 3, vy - 2, kx + 3, vy + 5, Draw.FILL_WHITE);
            }
            g.drawString(font, vol + "%", vx + vw + 5, vy - 2, Draw.FAINT, false);
        }
        if (TokenStore.hasLogin()) {
            String dev = (s == null || s.deviceName().isEmpty() ? "Devices" : s.deviceName()) + " ▴";
            int room = Math.max(24, vx - 10 - seekRight); // never run into the seek bar's time label
            String shown = Draw.ellipsize(font, dev, room);
            int dx = vx - 10 - font.width(shown);
            boolean dh = Draw.in(mx, my, dx - 2, vy - 4, font.width(shown) + 4, 12);
            g.drawString(font, shown, dx, vy - 2, dh || devicesOpen ? Draw.ACCENT : Draw.MUTED, false);
            hit(dx - 2, vy - 4, font.width(shown) + 4, 12, this::toggleDevices);
            devicesAnchor = new int[] {dx, py + ph}; // popup is clamped to sit just above the bar
        }

        // centre: transport and seek
        int ccx = x + w / 2, ccy = y + 16;
        int r = 9;
        boolean phv = Draw.in(mx, my, ccx - r, ccy - r, r * 2, r * 2);
        Draw.pill(g, ccx - r, ccy - r, ccx + r, ccy + r, phv ? Draw.FILL_WHITE : 0xFFDCDCE0);
        if (s != null && s.playing()) Draw.pause(g, ccx, ccy, 6, 0xFF000000);
        else Draw.triangleRight(g, ccx - 2, ccy, 8, 0xFF000000);
        hit(ccx - r, ccy - r, r * 2, r * 2, sp::togglePlay);
        iconButton(g, mx, my, ccx - 28, ccy, sp::previous, c -> Draw.prev(g, ccx - 28, ccy, c));
        iconButton(g, mx, my, ccx + 28, ccy, sp::next, c -> Draw.next(g, ccx + 28, ccy, c));
        glyphButton(g, mx, my, ccx - 54, ccy, "⇄", s != null && s.shuffle(), sp::toggleShuffle);
        glyphButton(g, mx, my, ccx + 54, ccy, s != null && "track".equals(s.repeat()) ? "↻1" : "↻",
                s != null && !"off".equals(s.repeat()), sp::cycleRepeat);

        int sx = ccx - sw / 2, sy = y + 36;
        long dur = t == null ? 0 : t.durationMs();
        long pos = drag == Drag.SEEK ? (long) (dragFrac * dur) : sp.position();
        double frac = dur > 0 ? pos / (double) dur : 0;
        if (t != null) seekRect = new int[] {sx, sy - 3, sw, 10};
        boolean sh = drag == Drag.SEEK || t != null && Draw.in(mx, my, sx, sy - 3, sw, 10);
        Draw.bar(g, sx, sy, sw, 3, frac, sh ? 0xFF4BEF86 : Draw.ACCENT);
        if (sh && dur > 0) {
            int kx = sx + (int) Math.round(frac * sw);
            Draw.round(g, kx - 3, sy - 2, kx + 3, sy + 5, Draw.FILL_WHITE);
        }
        String ps = PlaybackState.formatTime(pos), ds = PlaybackState.formatTime(dur);
        g.drawString(font, ps, sx - 6 - font.width(ps), sy - 2, Draw.FAINT, false);
        g.drawString(font, ds, sx + sw + 6, sy - 2, Draw.FAINT, false);
    }

    /** App layout's Now Playing view: large cover and track details, lyrics beside them. Controls live in the bar. */
    private void renderAppNowPlaying(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        if (!TokenStore.hasLogin()) {
            g.drawCenteredString(font, "Not connected to Spotify.", x + w / 2, y + h / 2 - 20, Draw.TEXT);
            button(g, mx, my, x + w / 2 - 50, y + h / 2 - 4, 100, 16, "Open Settings", true, () -> setTab(Tab.SETTINGS));
            return;
        }
        SpotifyPlayer sp = SpotifyPlayer.INSTANCE;
        PlaybackState s = sp.state();
        Track t = s == null ? null : s.track();
        int cover = clamp(Math.min(h - 56, (w - 24) * 2 / 5), 64, CoverArt.LARGE);
        int lx = x + 12, ly = y + 10;
        if (t == null) {
            g.drawString(font, sp.everPolled() ? "Nothing is playing on Spotify." : "Talking to Spotify…", lx, ly + 4, Draw.TEXT, false);
            int yy = ly + 18;
            for (FormattedCharSequence line : font.split(Component.literal(
                    "Pick something from your library on the left, search for a song, or choose a device."), Math.max(80, w - 30))) {
                g.drawString(font, line, lx, yy, Draw.MUTED, false);
                yy += 10;
            }
            button(g, mx, my, lx, yy + 6, 96, 16, "Choose device", true, this::toggleDevices);
            devicesAnchor = new int[] {lx, yy + 24};
            return;
        }
        Identifier id = CoverArt.INSTANCE.large();
        if (id != null) Draw.texture(g, id, lx, ly, cover, CoverArt.LARGE);
        else Draw.noCover(g, font, lx, ly, cover);
        int ty = ly + cover + 6;
        List<FormattedCharSequence> titleLines = font.split(Component.literal(t.title()), cover);
        int bw = t.explicit() ? Draw.explicitBadge(g, font, lx, ty - 1) : 0;
        for (int i = 0; i < Math.min(2, titleLines.size()); i++) {
            g.drawString(font, titleLines.get(i), lx + (i == 0 ? bw : 0), ty, Draw.TEXT, true);
            ty += 10;
        }
        g.drawString(font, Draw.ellipsize(font, t.artistLine(), cover), lx, ty + 1, Draw.MUTED, false);
        if (!t.album().isEmpty()) g.drawString(font, Draw.ellipsize(font, t.album(), cover), lx, ty + 12, Draw.FAINT, false);
        int lyricsX = lx + cover + 12, lyricsW = x + w - 8 - lyricsX;
        if (lyricsW >= 100) renderLyrics(g, mx, my, lyricsX, y + 6, lyricsW, h - 12, t);
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
        // left column and cover grow with the window; the controls below the cover need ~94 px
        int leftW = clamp(w * 9 / 20, 232, 380);
        int cover = clamp(Math.min(h - 104, leftW - 136), 96, CoverArt.LARGE);
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
        Draw.bar(g, lx, by, bw, 3, frac, sh ? 0xFF4BEF86 : Draw.ACCENT);
        if (sh && dur > 0) {
            int kx = lx + (int) Math.round(frac * bw);
            Draw.round(g, kx - 3, by - 2, kx + 3, by + 5, Draw.FILL_WHITE);
        }
        g.drawString(font, PlaybackState.formatTime(pos), lx, by + 7, Draw.FAINT, false);
        String ds = PlaybackState.formatTime(dur);
        g.drawString(font, ds, lx + bw - font.width(ds), by + 7, Draw.FAINT, false);

        // transport controls
        int ccx = lx + bw / 2, ccy = by + 31;
        int r = 11;
        boolean phv = Draw.in(mx, my, ccx - r, ccy - r, r * 2, r * 2);
        Draw.pill(g, ccx - r, ccy - r, ccx + r, ccy + r, phv ? Draw.FILL_WHITE : 0xFFDCDCE0);
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
            Draw.bar(g, vx, vy + 3, vw, 3, vol / 100.0, vh ? 0xFF4BEF86 : Draw.ACCENT);
            if (vh) {
                int kx = vx + (int) Math.round(vol / 100.0 * vw);
                Draw.round(g, kx - 3, vy + 1, kx + 3, vy + 8, Draw.FILL_WHITE);
            }
            g.drawString(font, vol + "%", vx + vw + 6, vy, Draw.MUTED, false);
        } else {
            g.drawString(font, "This device doesn't allow volume control", lx, vy, Draw.FAINT, false);
        }

        renderLyrics(g, mx, my, x + leftW, y + 6, w - leftW - 8, h - 10, t);
    }

    private void iconButton(GuiGraphics g, int mx, int my, int cx, int cy, Runnable action, Consumer<Integer> icon) {
        boolean hover = Draw.in(mx, my, cx - 9, cy - 9, 18, 18);
        icon.accept(hover ? Draw.FILL_WHITE : 0xFFC8C8D0);
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

        // wrapped rows, cached per track/version/width: re-wrapping the whole song every frame was the
        // screen's biggest per-frame cost
        boolean synced = ly.status() == LyricsService.Status.SYNCED;
        List<Lrc.Line> lines = ly.lrc().lines();
        String layoutKey = ly.trackUri() + "#" + ly.chosen() + "#" + ly.status() + "#" + aw + "#" + c.censorLyrics;
        if (!layoutKey.equals(panelKey)) {
            panelRows = new ArrayList<>();
            int yy = 0;
            int count = synced ? lines.size() : ly.plain().size();
            for (int i = 0; i < count; i++) {
                String text = synced ? lines.get(i).text() : ly.plain().get(i);
                if (text.isEmpty()) text = synced ? "♪" : " ";
                if (c.censorLyrics) text = dev.tunewire.core.Censor.clean(text);
                for (FormattedCharSequence part : font.split(Component.literal(text), aw)) {
                    panelRows.add(new PanelRow(part, i, yy, font.width(part)));
                    yy += 10;
                }
                yy += synced ? 4 : 1;
            }
            panelTotal = yy;
            panelKey = layoutKey;
        }
        List<PanelRow> rows = panelRows;
        int current = synced ? ly.lrc().indexAt(SpotifyPlayer.INSTANCE.position() + c.lyricsOffsetMs) : -1;
        int curTop = -1, curBottom = -1;
        for (PanelRow r : rows) {
            if (r.lineIdx() != current) continue;
            if (curTop < 0) curTop = r.y();
            curBottom = r.y() + 10;
        }
        int total = panelTotal;
        double max = Math.max(0, total - ah);
        if (synced && now - userScrolledAt > 4000) {
            double target = curTop < 0 ? 0 : (curTop + curBottom) / 2.0 - ah / 2.0;
            target = Math.max(0, Math.min(max, target));
            long nanos = System.nanoTime();
            double dt = lastPanelNanos == 0 ? 0 : Math.min(0.1, (nanos - lastPanelNanos) / 1e9);
            lyricScroll = lyricSnap ? target : lyricScroll + (target - lyricScroll) * (1 - Math.exp(-dt * 10)); // frame-rate independent glide
            lyricSnap = false;
        }
        lastPanelNanos = System.nanoTime();
        lyricScroll = Math.max(0, Math.min(max, lyricScroll));

        g.enableScissor(ax, ay, ax + aw, ay + ah);
        int hoverLine = -1;
        if (synced && Draw.in(mx, my, ax, ay, aw, ah)) {
            for (PanelRow r : rows) {
                int ry = ay + r.y() - (int) lyricScroll;
                if (my >= ry - 2 && my < ry + 12) hoverLine = r.lineIdx();
            }
        }
        for (PanelRow r : rows) {
            int ry = ay + r.y() - (int) lyricScroll;
            if (ry + 10 < ay || ry > ay + ah) continue;
            int color;
            if (!synced) color = 0xFFC8C8D0;
            else if (r.lineIdx() == current) color = Draw.TEXT;
            else if (r.lineIdx() == hoverLine) color = Draw.ACCENT;
            else if (r.lineIdx() < current) color = 0xFF6E6E78;
            else color = 0xFF9A9AA6;
            int tw = r.width();
            int rx = synced ? ax + (aw - tw) / 2 : ax;
            g.drawString(font, r.text(), rx, ry, color, synced && r.lineIdx() == current);
        }
        g.disableScissor();

        // click a synced line to jump there
        if (synced) {
            for (PanelRow r : rows) {
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

    // ---------------------------------------------------------------- queue

    private void renderQueue(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        SpotifyPlayer sp = SpotifyPlayer.INSTANCE;
        if (!TokenStore.hasLogin()) {
            g.drawCenteredString(font, "Connect Spotify in Settings to see your queue.", x + w / 2, y + h / 2 - 4, Draw.MUTED);
            return;
        }
        sp.wantQueue();
        int ax = x + 9, aw = w - 18;
        Track now = sp.track();

        // header row: what's playing now
        g.drawString(font, "NOW PLAYING", ax, y + 8, Draw.ACCENT, false);
        button(g, mx, my, ax + aw - 56, y + 4, 56, 14, sp.queueLoading() ? "…" : "Refresh", false, sp::refreshQueue);
        int ny = y + 20;
        if (now == null) {
            g.drawString(font, "Nothing is playing.", ax + 4, ny + 4, Draw.MUTED, false);
        } else {
            queueRow(g, ax, ny, aw, now, true, -1);
        }

        int ly = ny + 30;
        g.drawString(font, "NEXT UP", ax, ly, Draw.ACCENT, false);
        int ay = ly + 12, ah = y + h - 4 - ay;
        List<Track> q = shown(sp.queue());
        if (q.isEmpty()) {
            g.drawString(font, sp.queueLoading() ? "Loading the queue…" : "The queue is empty.", ax + 4, ay + 4, Draw.MUTED, false);
            return;
        }
        queueRect = new int[] {ax, ay, aw, ah};
        int rowH = 26;
        double max = Math.max(0, q.size() * rowH - ah);
        queueScroll = Math.max(0, Math.min(max, queueScroll));
        g.enableScissor(ax, ay, ax + aw, ay + ah);
        for (int i = 0; i < q.size(); i++) {
            int ry = ay + i * rowH - (int) queueScroll;
            if (ry + rowH < ay || ry > ay + ah) continue;
            queueRow(g, ax, ry, aw, q.get(i), false, i + 1);
        }
        g.disableScissor();
    }

    /** One queue entry: number (or a playing bar), title, artist, length. */
    private void queueRow(GuiGraphics g, int x, int y, int w, Track t, boolean playing, int number) {
        Draw.round(g, x, y, x + w, y + 24, playing ? 0xFF1F2A24 : Draw.CARD);
        int tx = x + 26;
        if (playing) {
            for (int b = 0; b < 3; b++) { // little equalizer bars
                int bh = 3 + (int) (Math.abs(Math.sin(System.currentTimeMillis() / 180.0 + b * 1.7)) * 7);
                g.fill(x + 8 + b * 4, y + 17 - bh, x + 11 + b * 4, y + 17, Draw.ACCENT);
            }
        } else {
            String n = String.valueOf(number);
            g.drawString(font, n, x + 14 - font.width(n) / 2, y + 8, Draw.FAINT, false);
        }
        String d = PlaybackState.formatTime(t.durationMs());
        int textW = w - 26 - font.width(d) - 14;
        int bw = t.explicit() ? Draw.explicitBadge(g, font, tx, y + 2) : 0;
        g.drawString(font, Draw.ellipsize(font, t.title(), textW - bw), tx + bw, y + 3, playing ? Draw.ACCENT : Draw.TEXT, false);
        g.drawString(font, Draw.ellipsize(font, t.artistLine(), textW), tx, y + 13, Draw.MUTED, false);
        g.drawString(font, d, x + w - 6 - font.width(d), y + 8, Draw.FAINT, false);
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
        // mode chips: songs / playlists / albums
        int chipX = fx;
        String[] labels = {"Songs", "Playlists", "Albums"};
        Collection.Kind[] kinds = {null, Collection.Kind.PLAYLIST, Collection.Kind.ALBUM};
        for (int i = 0; i < labels.length; i++) {
            int cw = font.width(labels[i]) + 12;
            boolean sel = searchKind == kinds[i];
            boolean hover = Draw.in(hmx, hmy, chipX, y + 30, cw, 13);
            Draw.pill(g, chipX, y + 30, chipX + cw, y + 43, sel ? Draw.ACCENT : hover ? 0xFF34374A : 0xFF2A2C3A);
            g.drawString(font, labels[i], chipX + 6, y + 33, sel ? 0xFF000000 : Draw.TEXT, false);
            Collection.Kind k = kinds[i];
            hit(chipX, y + 30, cw, 13, () -> {
                if (searchKind == k) return;
                searchKind = k;
                lastQuery = "";
                resultScroll = 0;
                runSearch(true);
            });
            chipX += cw + 4;
        }

        int ay = y + 48, ah = h - 50, ax = fx, aw = fw;
        boolean empty = searchKind == null ? results.isEmpty() : collResults.isEmpty();
        if (!searchStatus.isEmpty()) {
            g.drawCenteredString(font, searchStatus, x + w / 2, ay + 14, Draw.MUTED);
            if (empty) return;
            ay += 14;
            ah -= 14;
        } else if (empty) {
            String hint = searchKind == null ? "Type to search Spotify. Results play on your active device."
                    : "Type to search " + (searchKind == Collection.Kind.ALBUM ? "albums." : "playlists.");
            g.drawCenteredString(font, hint, x + w / 2, ay + 14, Draw.FAINT);
            return;
        }
        resultsRect = new int[] {ax, ay, aw, ah};
        int rowH = 24;
        List<Track> songs = shown(results);
        int count = searchKind == null ? songs.size() : collResults.size();
        double max = Math.max(0, count * rowH - ah);
        resultScroll = Math.max(0, Math.min(max, resultScroll));
        g.enableScissor(ax, ay, ax + aw, ay + ah);
        for (int i = 0; i < count; i++) {
            int ry = ay + i * rowH - (int) resultScroll;
            if (ry + rowH < ay || ry > ay + ah) continue;
            if (searchKind == null) {
                Track t = songs.get(i);
                trackRow(g, hmx, hmy, ax, ry, aw, ay, ah, t, -1, () -> SpotifyPlayer.INSTANCE.playTrack(t));
            } else {
                Collection c = collResults.get(i);
                collectionRow(g, hmx, hmy, ax, ry, aw, ay, ah, c, () -> {
                    Library.INSTANCE.openCollection(c);
                    libScroll = 0;
                    setTab(Tab.LIBRARY);
                });
            }
        }
        g.disableScissor();
    }

    /** A song row: title, artist · album, length; on hover ▶ and +Q buttons. {@code number} ≥ 1 shows a track number. */
    private void trackRow(GuiGraphics g, int hmx, int hmy, int ax, int ry, int aw, int ay, int ah, Track t, int number, Runnable play) {
        int rowH = 24;
        boolean inArea = hmy >= ay && hmy < ay + ah;
        boolean hover = inArea && Draw.in(hmx, hmy, ax, ry, aw, rowH - 2);
        Draw.round(g, ax, ry, ax + aw, ry + rowH - 2, hover ? Draw.CARD_HOVER : Draw.CARD);
        Track playing = SpotifyPlayer.INSTANCE.track();
        boolean isPlaying = playing != null && playing.uri().equals(t.uri());
        int tx = ax + 6;
        if (number >= 1) {
            String n = String.valueOf(number);
            g.drawString(font, n, ax + 12 - font.width(n) / 2, ry + 8, isPlaying ? Draw.ACCENT : Draw.FAINT, false);
            tx = ax + 24;
        }
        int textW = ax + aw - tx - (hover ? 74 : 38);
        int bw = t.explicit() ? Draw.explicitBadge(g, font, tx, ry + 2) : 0;
        g.drawString(font, Draw.ellipsize(font, t.title(), textW - bw), tx + bw, ry + 3, isPlaying ? Draw.ACCENT : Draw.TEXT, false);
        String sub = t.artistLine() + (t.album().isEmpty() || number >= 1 ? "" : "  ·  " + t.album());
        g.drawString(font, Draw.ellipsize(font, sub, textW), tx, ry + 13, Draw.MUTED, false);
        int top = Math.max(ay, ry), bottom = Math.min(ay + ah, ry + rowH - 2);
        if (bottom > top) hit(ax, top, aw, bottom - top, play);
        if (hover) {
            int bx = ax + aw - 66;
            Draw.round(g, bx, ry + 5, bx + 30, ry + 17, Draw.ACCENT);
            Draw.triangleRight(g, bx + 12, ry + 11, 6, 0xFF000000);
            Draw.round(g, bx + 34, ry + 5, bx + 62, ry + 17, 0xFF34374A);
            g.drawCenteredString(font, "+Q", bx + 48, ry + 7, Draw.TEXT);
            if (bottom > top) {
                hit(bx, Math.max(ay, ry + 5), 30, 12, play);
                hit(bx + 34, Math.max(ay, ry + 5), 28, 12, () -> SpotifyPlayer.INSTANCE.queue(t));
            }
        } else {
            String d = PlaybackState.formatTime(t.durationMs());
            g.drawString(font, d, ax + aw - 6 - font.width(d), ry + 8, Draw.FAINT, false);
        }
    }

    /** A playlist/album row: name, owner or artist · song count; click opens it, ▶ plays it. */
    private void collectionRow(GuiGraphics g, int hmx, int hmy, int ax, int ry, int aw, int ay, int ah, Collection c, Runnable open) {
        int rowH = 24;
        boolean inArea = hmy >= ay && hmy < ay + ah;
        boolean hover = inArea && Draw.in(hmx, hmy, ax, ry, aw, rowH - 2);
        Draw.round(g, ax, ry, ax + aw, ry + rowH - 2, hover ? Draw.CARD_HOVER : Draw.CARD);
        String icon = switch (c.kind()) {
            case LIKED -> "♥";
            case ALBUM -> "◉";
            case PLAYLIST -> "≡";
        };
        g.drawString(font, icon, ax + 8, ry + 8, c.kind() == Collection.Kind.LIKED ? Draw.ACCENT : Draw.MUTED, false);
        int tx = ax + 22, textW = aw - 22 - (hover ? 44 : 10);
        g.drawString(font, Draw.ellipsize(font, c.name(), textW), tx, ry + 3, Draw.TEXT, false);
        String sub = c.subtitle() + (c.total() >= 0 ? (c.subtitle().isEmpty() ? "" : "  ·  ") + c.total() + (c.total() == 1 ? " song" : " songs") : "");
        g.drawString(font, Draw.ellipsize(font, sub, textW), tx, ry + 13, Draw.MUTED, false);
        int top = Math.max(ay, ry), bottom = Math.min(ay + ah, ry + rowH - 2);
        if (bottom > top) hit(ax, top, aw, bottom - top, open);
        if (hover && c.kind() != Collection.Kind.LIKED) {
            int bx = ax + aw - 36;
            Draw.round(g, bx, ry + 5, bx + 30, ry + 17, Draw.ACCENT);
            Draw.triangleRight(g, bx + 12, ry + 11, 6, 0xFF000000);
            if (bottom > top) hit(bx, Math.max(ay, ry + 5), 30, 12, () -> SpotifyPlayer.INSTANCE.playCollection(c, List.of(), null));
        }
    }

    // ---------------------------------------------------------------- library

    private void renderLibrary(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        if (!TokenStore.hasLogin()) {
            g.drawCenteredString(font, "Connect Spotify in Settings to see your library.", x + w / 2, y + h / 2 - 4, Draw.MUTED);
            return;
        }
        if (!TokenStore.hasLibraryScope()) {
            int yy = y + h / 2 - 30;
            for (FormattedCharSequence line : font.split(Component.literal(
                    "Tunewire needs permission to read your playlists and saved music. Reconnect once to allow it."), w - 60)) {
                g.drawCenteredString(font, line, x + w / 2, yy, Draw.MUTED);
                yy += 10;
            }
            button(g, mx, my, x + w / 2 - 60, yy + 8, 120, 16, "Reconnect Spotify", true, this::connect);
            return;
        }
        Library lib = Library.INSTANCE;
        lib.ensureRoot();
        if (lib.open() != null) {
            renderCollection(g, mx, my, x, y, w, h, lib.open());
            return;
        }
        int ax = x + 9, aw = w - 18;
        g.drawString(font, "YOUR LIBRARY", ax, y + 8, Draw.ACCENT, false);
        button(g, mx, my, ax + aw - 56, y + 4, 56, 14, "Refresh", false, lib::refresh);

        int ay = y + 22, ah = h - 24;
        libRect = new int[] {ax, ay, aw, ah};
        Library.ListState<Collection> pl = lib.playlists(), al = lib.albums();
        int rowH = 24, headH = 14;
        // total height first, to clamp the scroll
        int total = rowH + headH + Math.max(1, pl.items().size()) * rowH + (pl.hasMore() || pl.loading() ? 14 : 0)
                + headH + Math.max(1, al.items().size()) * rowH + (al.hasMore() || al.loading() ? 14 : 0);
        double max = Math.max(0, total - ah);
        libScroll = Math.max(0, Math.min(max, libScroll));
        int ry = ay - (int) libScroll;
        g.enableScissor(ax, ay, ax + aw, ay + ah);

        Collection liked = Collection.liked(lib.likedTotal());
        collectionRow(g, mx, my, ax, ry, aw, ay, ah, liked, () -> openLib(liked));
        ry += rowH;

        ry = libSection(g, mx, my, ax, ry, aw, ay, ah, "PLAYLISTS", pl, "No playlists yet.", lib::morePlaylists);
        ry = libSection(g, mx, my, ax, ry, aw, ay, ah, "SAVED ALBUMS", al, "No saved albums.", lib::moreAlbums);
        g.disableScissor();
    }

    private int libSection(GuiGraphics g, int mx, int my, int ax, int ry, int aw, int ay, int ah, String title,
                           Library.ListState<Collection> s, String emptyText, Runnable more) {
        g.drawString(font, title, ax + 2, ry + 4, Draw.FAINT, false);
        ry += 14;
        if (s.items().isEmpty()) {
            String msg = !s.error().isEmpty() ? s.error() : s.loading() || s.total() < 0 ? "Loading…" : emptyText;
            g.drawString(font, Draw.ellipsize(font, msg, aw - 8), ax + 6, ry + 8, s.error().isEmpty() ? Draw.MUTED : Draw.BAD, false);
            return ry + 24;
        }
        for (Collection c : s.items()) {
            if (ry + 24 >= ay && ry <= ay + ah) collectionRow(g, mx, my, ax, ry, aw, ay, ah, c, () -> openLib(c));
            ry += 24;
        }
        if (s.hasMore() || s.loading()) {
            g.drawString(font, s.loading() ? "Loading more…" : "", ax + 6, ry + 2, Draw.FAINT, false);
            if (ry < ay + ah + 40) more.run(); // next page as the end scrolls into view
            ry += 14;
        }
        return ry;
    }

    private void openLib(Collection c) {
        Library.INSTANCE.openCollection(c);
        libScroll = 0;
    }

    private void renderCollection(GuiGraphics g, int mx, int my, int x, int y, int w, int h, Collection c) {
        Library lib = Library.INSTANCE;
        int ax = x + 9, aw = w - 18;
        button(g, mx, my, ax, y + 5, 40, 14, "‹ Back", false, () -> {
            lib.close();
            libScroll = 0;
        });
        Library.ListState<Track> s = lib.openTracks();
        boolean canPlay = c.kind() != Collection.Kind.LIKED || !s.items().isEmpty();
        int playW = 50;
        if (canPlay) button(g, mx, my, ax + aw - playW, y + 5, playW, 14, "▶ Play", true,
                () -> SpotifyPlayer.INSTANCE.playCollection(c, s.items(), null));
        int nameW = aw - 48 - playW - 8;
        g.drawString(font, Draw.ellipsize(font, c.name(), nameW), ax + 48, y + 4, Draw.TEXT, true);
        String sub = c.subtitle() + (c.total() >= 0 ? (c.subtitle().isEmpty() ? "" : "  ·  ") + c.total() + " songs" : "");
        g.drawString(font, Draw.ellipsize(font, sub, nameW), ax + 48, y + 14, Draw.MUTED, false);

        int ay = y + 28, ah = h - 30;
        String note = lib.openNote();
        if (!note.isEmpty()) {
            for (FormattedCharSequence line : font.split(Component.literal(note), aw - 8)) {
                g.drawString(font, line, ax + 4, ay + 2, Draw.MUTED, false);
                ay += 10;
                ah -= 10;
            }
            ay += 4;
            ah -= 4;
        }
        if (s.items().isEmpty()) {
            if (note.isEmpty()) {
                String msg = !s.error().isEmpty() ? s.error() : s.loading() || s.total() < 0 ? "Loading songs…" : "No songs here.";
                g.drawString(font, Draw.ellipsize(font, msg, aw - 8), ax + 4, ay + 6, s.error().isEmpty() ? Draw.MUTED : Draw.BAD, false);
            }
            return;
        }
        libRect = new int[] {ax, ay, aw, ah};
        int rowH = 24;
        List<Track> items = shown(s.items());
        int total = items.size() * rowH + (s.hasMore() || s.loading() ? 14 : 0);
        double max = Math.max(0, total - ah);
        libScroll = Math.max(0, Math.min(max, libScroll));
        g.enableScissor(ax, ay, ax + aw, ay + ah);
        int first = Math.max(0, (int) (libScroll / rowH)), last = Math.min(items.size() - 1, (int) ((libScroll + ah) / rowH));
        for (int i = first; i <= last; i++) { // only visible rows: Liked Songs can be thousands long
            int ry = ay + i * rowH - (int) libScroll;
            Track t = items.get(i);
            trackRow(g, mx, my, ax, ry, aw, ay, ah, t, c.kind() == Collection.Kind.ALBUM ? i + 1 : -1,
                    () -> SpotifyPlayer.INSTANCE.playCollection(c, items, t));
        }
        int endY = ay + items.size() * rowH - (int) libScroll;
        if (s.hasMore() || s.loading()) {
            g.drawString(font, s.loading() ? "Loading more…" : "", ax + 6, endY + 2, Draw.FAINT, false);
            if (endY < ay + ah + 60) lib.moreTracks();
        }
        g.disableScissor();
    }

    // ---------------------------------------------------------------- settings

    private void renderSettings(GuiGraphics g, int mx, int my, int rmx, int rmy, float delta, int x, int y, int w, int h) {
        TwConfig c = TwConfig.get();
        int lx = x + 10, lw = settingsLeftW(w) - 18;
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
        int rx = x + settingsLeftW(w) + 4, rw = w - settingsLeftW(w) - 16;
        g.fill(rx - 8, y + 8, rx - 7, y + h - 8, Draw.BORDER);
        // the column scrolls: rows outside it are clipped and their clicks dropped
        int areaTop = y + 4, areaBottom = y + h - 4;
        settingsRect = new int[] {rx - 6, areaTop, rw + 12, areaBottom - areaTop};
        int hitsBefore = hits.size();
        int ry0 = y + 10 - (int) settingsScroll;
        int ry = ry0;
        rowScale = 1.0;
        g.enableScissor(rx - 6, areaTop, rx + rw + 6, areaBottom);
        g.drawString(font, "HUD", rx, ry, Draw.ACCENT, false);
        ry += sp(14);
        toggle(g, mx, my, rx, ry, rw, "Now playing card", () -> c.nowPlayingHud, v -> c.nowPlayingHud = v);
        ry += sp(15);
        toggle(g, mx, my, rx, ry, rw, "Cover art on the card", () -> c.hudCoverArt, v -> c.hudCoverArt = v);
        ry += sp(15);
        toggle(g, mx, my, rx, ry, rw, "Hide HUD while paused", () -> c.hideHudWhenPaused, v -> c.hideHudWhenPaused = v);
        ry += sp(15);
        button(g, mx, my, rx, ry, rw, 16, "Edit HUD layout", false, () -> minecraft.setScreen(new HudEditorScreen(this)));
        ry += sp(24);
        g.drawString(font, "LYRICS", rx, ry, Draw.ACCENT, false);
        ry += sp(14);
        toggle(g, mx, my, rx, ry, rw, "Show lyrics", () -> c.lyricsHud, v -> c.lyricsHud = v);
        ry += sp(15);
        toggle(g, mx, my, rx, ry, rw, "Show lines before and after", () -> c.lyricsLines == 3, v -> c.lyricsLines = v ? 3 : 1);
        ry += sp(15);
        toggle(g, mx, my, rx, ry, rw, "Shade behind current line", () -> c.lyricsBackground, v -> c.lyricsBackground = v);
        ry += sp(15);
        // lyric style: HUD box / placed in the world / floating with the view
        g.drawString(font, "Style", rx, ry + 1, Draw.MUTED, false);
        String styleLabel = TwConfig.lyricsModeLabel(c.lyricsMode);
        int sbw = Math.min(rw - 40, font.width(styleLabel) + 16);
        button(g, mx, my, rx + rw - sbw, ry - 2, sbw, 13, styleLabel, false, () -> {
            c.cycleLyricsMode();
            TwConfig.save();
            if (c.lyricsIn3d()) WorldLyrics.preview();
        });
        ry += sp(16);
        if (c.lyricsIn3d()) {
            ry = worldStepper(g, mx, my, rx, ry, rw, "  Distance", String.format("%.1f", c.worldDistance),
                    () -> c.worldDistance = Math.max(1.5f, c.worldDistance - 0.5f),
                    () -> c.worldDistance = Math.min(8f, c.worldDistance + 0.5f));
            ry = worldStepper(g, mx, my, rx, ry, rw, "  Left / right", String.format("%+.2f", c.worldOffsetX),
                    () -> c.worldOffsetX = Math.max(-4f, c.worldOffsetX - 0.25f),
                    () -> c.worldOffsetX = Math.min(4f, c.worldOffsetX + 0.25f));
            ry = worldStepper(g, mx, my, rx, ry, rw, "  Up / down", String.format("%+.2f", c.worldOffsetY),
                    () -> c.worldOffsetY = Math.max(-4f, c.worldOffsetY - 0.25f),
                    () -> c.worldOffsetY = Math.min(4f, c.worldOffsetY + 0.25f));
            ry = worldStepper(g, mx, my, rx, ry, rw, "  Text size", Math.round(c.worldScale * 100) + "%",
                    () -> c.worldScale = Math.max(0.5f, Math.round((c.worldScale - 0.1f) * 10) / 10f),
                    () -> c.worldScale = Math.min(2.5f, Math.round((c.worldScale + 0.1f) * 10) / 10f));
            toggle(g, mx, my, rx, ry, rw, "  Visible through blocks", () -> c.worldThroughWalls, v -> {
                c.worldThroughWalls = v;
                WorldLyrics.preview();
            });
            ry += sp(15);
        }
        ry += 1;
        stepper(g, mx, my, rx, ry, rw, "Lyrics offset", String.format("%+.2fs", c.lyricsOffsetMs / 1000.0),
                () -> shiftOffset(-250), () -> shiftOffset(250));
        ry += sp(18);
        g.drawString(font, "EXPLICIT CONTENT", rx, ry, Draw.ACCENT, false);
        ry += sp(14);
        toggle(g, mx, my, rx, ry, rw, "Censor explicit lyrics", () -> c.censorLyrics, v -> c.censorLyrics = v);
        ry += sp(15);
        toggle(g, mx, my, rx, ry, rw, "Hide explicit songs in lists", () -> c.hideExplicit, v -> c.hideExplicit = v);
        ry += sp(15);
        toggle(g, mx, my, rx, ry, rw, "Skip explicit songs", () -> c.skipExplicit, v -> c.skipExplicit = v);
        ry += sp(18);
        g.drawString(font, "CONTROLS", rx, ry, Draw.ACCENT, false);
        ry += sp(14);
        // off = the tabs layout as before; on = sidebar + bottom playback bar
        toggle(g, mx, my, rx, ry, rw, "Spotify-style player layout", () -> c.appLayout(), v -> {
            c.playerLayout = v ? "app" : "tabs";
            devicesOpen = false;
        });
        ry += sp(15);
        stepper(g, mx, my, rx, ry, rw, "Volume key step", c.volumeStep + "%", () -> {
            c.volumeStep = Math.max(1, c.volumeStep - (c.volumeStep > 5 ? 5 : 1));
            TwConfig.save();
        }, () -> {
            c.volumeStep = Math.min(25, c.volumeStep + (c.volumeStep >= 5 ? 5 : 1));
            TwConfig.save();
        });
        ry += sp(16);
        g.drawString(font, Draw.ellipsize(font, "Hotkeys: Options → Controls → Tunewire", rw), rx, ry, Draw.FAINT, false);
        ry += 12;
        g.disableScissor();

        int total = ry - ry0 + 6;
        double max = Math.max(0, total - (areaBottom - areaTop));
        settingsScroll = Math.max(0, Math.min(max, settingsScroll));
        hits.subList(hitsBefore, hits.size()).removeIf(hh -> hh.y() < areaTop || hh.y() + hh.h() > areaBottom);
        if (max > 0) { // scrollbar
            int trackX = rx + rw + 4, ah = areaBottom - areaTop;
            int thumbH = Math.max(14, (int) (ah * (ah / (double) total)));
            int thumbY = areaTop + (int) ((ah - thumbH) * (settingsScroll / max));
            g.fill(trackX, areaTop, trackX + 2, areaBottom, 0xFF22242E);
            g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xFF5A5D70);
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
        TwConfig cfg = TwConfig.get();
        if (!cfg.playerFit && Draw.in(mx, my, px + pw - GRIP, py + ph - GRIP, GRIP, GRIP)) {
            drag = Drag.RESIZE;
            cfg.playerX = px; // anchor the top-left so the window grows toward the cursor
            cfg.playerY = py;
            grabX = mx - pw;
            grabY = my - ph;
            return true;
        }
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
        if (Draw.in(mx, my, px, py, pw, HEADER)) {
            if (dbl) {
                toggleFit(); // double-click the title bar, like a desktop window
                return true;
            }
            if (!cfg.playerFit) {
                drag = Drag.MOVE;
                grabX = mx - px;
                grabY = my - py;
            }
            return true;
        }
        return false;
    }

    private static double frac(double mx, int[] r) {
        return Math.max(0, Math.min(1, (mx - r[0]) / (double) r[2]));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        TwConfig cfg = TwConfig.get();
        if (drag == Drag.MOVE) {
            cfg.playerX = clamp((int) (e.x() - grabX), 0, Math.max(0, width - pw));
            cfg.playerY = clamp((int) (e.y() - grabY), 0, Math.max(0, height - ph));
            return true;
        }
        if (drag == Drag.RESIZE) {
            cfg.playerW = clamp((int) (e.x() - grabX), MIN_W, Math.max(MIN_W, width - px));
            cfg.playerH = clamp((int) (e.y() - grabY), MIN_H, Math.max(MIN_H, height - py));
            return true;
        }
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
        if (drag == Drag.MOVE || drag == Drag.RESIZE) TwConfig.save();
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
        if (settingsRect != null && Draw.in(mx, my, settingsRect[0], settingsRect[1], settingsRect[2], settingsRect[3])) {
            settingsScroll -= sy * 20;
            return true;
        }
        if (sideRect != null && Draw.in(mx, my, sideRect[0], sideRect[1], sideRect[2], sideRect[3])) {
            sideScroll -= sy * 20;
            return true;
        }
        if (libRect != null && Draw.in(mx, my, libRect[0], libRect[1], libRect[2], libRect[3])) {
            libScroll -= sy * 24;
            return true;
        }
        if (queueRect != null && Draw.in(mx, my, queueRect[0], queueRect[1], queueRect[2], queueRect[3])) {
            queueScroll -= sy * 24;
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

    /** The list without explicit tracks when "Hide explicit songs" is on. */
    private static List<Track> shown(List<Track> list) {
        if (!TwConfig.get().hideExplicit) return list;
        List<Track> out = new ArrayList<>(list.size());
        for (Track t : list) if (!t.explicit()) out.add(t);
        return out;
    }

    /** A 3D-lyrics placement stepper: saves and shows the lyrics in the world while you adjust. Returns the next row y. */
    private int worldStepper(GuiGraphics g, int mx, int my, int rx, int ry, int rw, String label, String value,
                             Runnable minus, Runnable plus) {
        stepper(g, mx, my, rx, ry, rw, label, value, () -> {
            minus.run();
            TwConfig.save();
            WorldLyrics.preview();
        }, () -> {
            plus.run();
            TwConfig.save();
            WorldLyrics.preview();
        });
        return ry + sp(16);
    }

    /** The Settings tab is open with a 3D lyric style: the world shows through and sample lyrics stay visible. */
    public boolean adjustingWorldLyrics() {
        return tab == Tab.SETTINGS && TwConfig.get().lyricsIn3d();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        if (adjustingWorldLyrics()) {
            g.fill(0, 0, width, height, 0x28000000); // no blur, so placement changes can be seen in the world
            return;
        }
        super.renderBackground(g, mx, my, delta);
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
