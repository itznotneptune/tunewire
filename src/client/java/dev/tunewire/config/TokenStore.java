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

/**
 * Spotify tokens, in config/tunewire-auth.json. Kept out of tunewire.json so the settings file can be
 * shared without handing over account access.
 */
public final class TokenStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("tunewire-auth.json");

    public static final class Tokens {
        public String clientId = "";
        public String accessToken = "";
        public String refreshToken = "";
        /** Epoch millis the access token stops working. */
        public long expiresAt;
    }

    private static volatile Tokens tokens = new Tokens();

    private TokenStore() {}

    public static synchronized Tokens get() {
        return tokens;
    }

    public static boolean hasLogin() {
        Tokens t = tokens; // volatile read; called every frame
        return t.refreshToken != null && !t.refreshToken.isEmpty();
    }

    public static synchronized void load() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Tokens t = GSON.fromJson(r, Tokens.class);
            if (t != null) tokens = t;
        } catch (Exception e) {
            TunewireClient.LOG.warn("Could not read {}", FILE, e);
        }
    }

    /** Swaps tokens under the lock, writes the file outside it so the render thread never waits on disk. */
    public static void set(Tokens t) {
        synchronized (TokenStore.class) {
            tokens = t;
        }
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(t, w);
            }
        } catch (Exception e) {
            TunewireClient.LOG.warn("Could not save {}", FILE, e);
        }
    }

    public static synchronized void clear() {
        tokens = new Tokens();
        try {
            Files.deleteIfExists(FILE);
        } catch (Exception e) {
            TunewireClient.LOG.warn("Could not delete {}", FILE, e);
        }
    }
}
