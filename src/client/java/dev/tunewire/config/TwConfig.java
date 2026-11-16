package dev.tunewire.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.tunewire.TunewireClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Player-facing settings, saved to config/tunewire.json. Tokens live separately in {@link TokenStore}. */
public final class TwConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("tunewire.json");
    private static TwConfig instance = new TwConfig();

    /** Client ID of the player's own Spotify developer app. */
    public String clientId = "";

    public boolean nowPlayingHud = true;
    public boolean hudCoverArt = true;
    public boolean hideHudWhenPaused = false;
    public boolean lyricsHud = true;
    /** 1 = current line only, 3 = previous / current / next. */
    public int lyricsLines = 3;
    public boolean lyricsBackground = true;
    /** Lyrics float as 3D text in the world in front of you instead of on the HUD. */
    public boolean lyricsInWorld = false;
    /** Blocks between your eyes and the floating lyrics. */
    public float worldDistance = 3.5f;
    /** Size multiplier of the floating lyrics. */
    public float worldScale = 1.0f;
    /** Floating lyrics stay visible through blocks in front of them. */
    public boolean worldThroughWalls = true;
    /** Floating lyrics offset from the centre of view, in blocks: positive = right / up. */
    public float worldOffsetX = 0f;
    public float worldOffsetY = 0f;
    /**
     * How the lyrics are shown: "hud" (box above the hotbar), "world" (3D text that follows your view) or
     * "placed" (each line is set down in the world where you are looking when it starts, and stays there).
     */
    public String lyricsMode = "";

    /** Explicit content: mask swear words in lyrics. */
    public boolean censorLyrics = false;
    /** Explicit content: leave explicit tracks out of search, library and queue lists. */
    public boolean hideExplicit = false;
    /** Explicit content: skip to the next track when an explicit one starts. */
    public boolean skipExplicit = false;
    /** Added to the playback position when picking the lyric line; positive shows lines earlier. */
    public int lyricsOffsetMs = 0;
    public int volumeStep = 10;

    /** Player window size in GUI pixels (0 = default) and top-left (-1 = centred). */
    public int playerW = 0;
    public int playerH = 0;
    public int playerX = -1;
    public int playerY = -1;
    /** Player window fills the screen; the custom size is kept for when this is turned off. */
    public boolean playerFit = false;
    /** Player layout: "tabs" (tabs across the top) or "app" (sidebar with your library, bottom playback bar). */
    public String playerLayout = "tabs";

    public boolean appLayout() {
        return "app".equals(playerLayout);
    }

    public HudPos nowPlayingPos = new HudPos(6, 6, 1f);
    /** x is the horizontal centre of the lyrics; negative means "centre of the screen". */
    public HudPos lyricsPos = new HudPos(-1, -1, 1f);

    public static TwConfig get() {
        return instance;
    }

    public static void load() {
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                TwConfig c = GSON.fromJson(r, TwConfig.class);
                if (c != null) instance = c;
            } catch (Exception e) {
                TunewireClient.LOG.warn("Could not read {}; using defaults", FILE, e);
            }
        }
        instance.fix();
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, w);
            }
        } catch (Exception e) {
            TunewireClient.LOG.warn("Could not save {}", FILE, e);
        }
    }

    private void fix() {
        if (clientId == null) clientId = "";
        clientId = clientId.strip();
        if (nowPlayingPos == null) nowPlayingPos = new HudPos(6, 6, 1f);
        if (lyricsPos == null) lyricsPos = new HudPos(-1, -1, 1f);
        lyricsLines = lyricsLines <= 1 ? 1 : 3;
        lyricsOffsetMs = Math.max(-5000, Math.min(5000, lyricsOffsetMs));
        volumeStep = Math.max(1, Math.min(25, volumeStep));
        worldDistance = Math.max(1.5f, Math.min(8f, worldDistance));
        worldScale = Math.max(0.5f, Math.min(2.5f, worldScale));
        worldOffsetX = Math.max(-4f, Math.min(4f, worldOffsetX));
        worldOffsetY = Math.max(-4f, Math.min(4f, worldOffsetY));
        if (lyricsMode == null || !(lyricsMode.equals("hud") || lyricsMode.equals("placed") || lyricsMode.equals("world"))) {
            lyricsMode = lyricsInWorld ? "world" : "hud"; // settings files from 0.2/0.3 only had the world toggle
        }
        lyricsInWorld = lyricsMode.equals("world");
        if (!"app".equals(playerLayout)) playerLayout = "tabs";
        playerW = Math.max(0, playerW);
        playerH = Math.max(0, playerH);
    }

    /** hud → placed → world → hud; keeps the legacy world flag in step. */
    public void cycleLyricsMode() {
        lyricsMode = switch (lyricsMode) {
            case "hud" -> "placed";
            case "placed" -> "world";
            default -> "hud";
        };
        lyricsInWorld = lyricsMode.equals("world");
    }

    /** Lyrics are drawn in the 3D world (following or placed), not on the HUD. */
    public boolean lyricsIn3d() {
        return !"hud".equals(lyricsMode);
    }

    public static String lyricsModeLabel(String mode) {
        return switch (mode) {
            case "placed" -> "Placed in world";
            case "world" -> "Floating, follows view";
            default -> "HUD box";
        };
    }

    public static final class HudPos {
        public int x;
        public int y;
        public float scale;

        public HudPos(int x, int y, float scale) {
            this.x = x;
            this.y = y;
            this.scale = scale;
        }
    }
}
