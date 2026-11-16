package dev.tunewire.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tunewire.TunewireClient;
import dev.tunewire.config.TokenStore;
import dev.tunewire.config.TwConfig;
import dev.tunewire.core.Http;
import dev.tunewire.core.Json;
import dev.tunewire.core.Pkce;
import net.minecraft.util.Util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Spotify Authorization Code with PKCE. No client secret: the player registers their own Spotify app
 * and pastes its Client ID. The redirect lands on a one-shot listener bound to 127.0.0.1 only.
 */
public final class SpotifyAuth {
    public static final int PORT = 43827;
    public static final String REDIRECT_URI = "http://127.0.0.1:" + PORT + "/callback";
    public static final String DASHBOARD = "https://developer.spotify.com/dashboard";
    private static final String SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final long LOGIN_TIMEOUT_MS = 5 * 60_000;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static volatile Thread loginThread;
    private static volatile ServerSocket loginSocket;
    private static CompletableFuture<String> refreshing;

    private SpotifyAuth() {}

    public static boolean loginInProgress() {
        Thread t = loginThread;
        return t != null && t.isAlive();
    }

    /** Opens the browser at Spotify's consent page and waits for the redirect in the background. */
    public static void startLogin() {
        String clientId = TwConfig.get().clientId;
        if (clientId.isEmpty()) {
            TunewireClient.chat("Set your Spotify Client ID first (Player → Settings, or /tunewire clientid <id>).", true);
            return;
        }
        cancelLogin();
        String verifier = Pkce.verifier();
        String state = Pkce.randomToken(16);
        ServerSocket socket;
        try {
            socket = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
            socket.setSoTimeout(1000);
        } catch (Exception e) {
            TunewireClient.chat("Could not listen on 127.0.0.1:" + PORT + " for the Spotify redirect (" + e.getMessage() + ").", true);
            return;
        }
        loginSocket = socket;
        String url = "https://accounts.spotify.com/authorize?" + Http.form(orderedMap(
                "response_type", "code",
                "client_id", clientId,
                "scope", SCOPES,
                "redirect_uri", REDIRECT_URI,
                "state", state,
                "code_challenge_method", "S256",
                "code_challenge", Pkce.challenge(verifier)));
        Thread t = new Thread(() -> awaitRedirect(socket, clientId, verifier, state), "Tunewire login");
        t.setDaemon(true);
        loginThread = t;
        t.start();
        Util.getPlatform().openUri(url);
        TunewireClient.chat("Opened Spotify in your browser. Approve access there, then come back.", false);
    }

    public static void cancelLogin() {
        ServerSocket s = loginSocket;
        loginSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void awaitRedirect(ServerSocket socket, String clientId, String verifier, String state) {
        long deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;
        try (socket) {
            while (System.currentTimeMillis() < deadline && !socket.isClosed()) {
                Socket c;
                try {
                    c = socket.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                try (c) {
                    c.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
                    String target = Http.requestTarget(in.readLine());
                    if (target == null || !target.startsWith("/callback")) {
                        respond(c, 404, "Not found");
                        continue; // e.g. the browser asking for /favicon.ico
                    }
                    int q = target.indexOf('?');
                    Map<String, String> params = Http.parseQuery(q < 0 ? "" : target.substring(q + 1));
                    if (!state.equals(params.get("state"))) {
                        respond(c, 400, "This login link is stale. Start the login again from Minecraft.");
                        continue;
                    }
                    String error = params.get("error");
                    if (error != null) {
                        respond(c, 200, "Spotify login cancelled (" + error + "). You can close this tab.");
                        TunewireClient.chat("Spotify login cancelled: " + error, true);
                        return;
                    }
                    String code = params.getOrDefault("code", "");
                    try {
                        exchange(clientId, code, verifier);
                    } catch (Exception e) {
                        respond(c, 500, "Login failed: " + e.getMessage());
                        TunewireClient.chat("Spotify login failed: " + e.getMessage(), true);
                        return;
                    }
                    respond(c, 200, "Tunewire is connected to Spotify. You can close this tab and go back to Minecraft.");
                    TunewireClient.chat("Connected to Spotify.", false);
                    SpotifyPlayer.INSTANCE.pollSoon(0);
                    return;
                }
            }
            if (System.currentTimeMillis() >= deadline) TunewireClient.chat("Spotify login timed out. Try again.", true);
        } catch (Exception e) {
            if (!socket.isClosed()) TunewireClient.LOG.warn("Login listener failed", e);
        } finally {
            if (loginSocket == socket) loginSocket = null;
        }
    }

    private static void respond(Socket c, int status, String message) {
        String html = "<!doctype html><meta charset=utf-8><title>Tunewire</title>"
                + "<body style=\"font-family:system-ui,sans-serif;background:#121318;color:#eee;display:grid;place-items:center;height:90vh\">"
                + "<p style=\"font-size:18px\">" + message.replace("<", "&lt;") + "</p>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n";
        try {
            OutputStream out = c.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static void exchange(String clientId, String code, String verifier) throws Exception {
        JsonObject o = postToken(orderedMap(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", REDIRECT_URI,
                "client_id", clientId,
                "code_verifier", verifier));
        TokenStore.Tokens t = new TokenStore.Tokens();
        t.clientId = clientId;
        store(t, o);
    }

    private static void store(TokenStore.Tokens t, JsonObject o) {
        t.accessToken = Json.str(o, "access_token");
        String refresh = Json.str(o, "refresh_token");
        if (!refresh.isEmpty()) t.refreshToken = refresh; // Spotify may or may not rotate it
        t.expiresAt = System.currentTimeMillis() + Json.lng(o, "expires_in", 3600) * 1000;
        TokenStore.set(t);
    }

    private static JsonObject postToken(Map<String, String> form) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(Http.form(form)))
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject o;
        try {
            o = JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("HTTP " + res.statusCode());
        }
        if (res.statusCode() != 200) {
            String desc = Json.str(o, "error_description");
            throw new IllegalStateException(desc.isEmpty() ? Json.str(o, "error") + " (HTTP " + res.statusCode() + ")" : desc);
        }
        return o;
    }

    /**
     * A valid access token, refreshing it first when it is within a minute of expiring.
     * Concurrent callers share one refresh. Completes exceptionally when not logged in or refresh fails.
     */
    public static synchronized CompletableFuture<String> accessToken(boolean forceRefresh) {
        TokenStore.Tokens t = TokenStore.get();
        if (t.refreshToken == null || t.refreshToken.isEmpty()) {
            return CompletableFuture.failedFuture(new NotLoggedIn());
        }
        if (!forceRefresh && t.accessToken != null && !t.accessToken.isEmpty() && System.currentTimeMillis() < t.expiresAt - 60_000) {
            return CompletableFuture.completedFuture(t.accessToken);
        }
        if (refreshing != null && !refreshing.isDone()) return refreshing;
        String clientId = t.clientId == null || t.clientId.isEmpty() ? TwConfig.get().clientId : t.clientId;
        String refreshToken = t.refreshToken;
        refreshing = CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject o = postToken(orderedMap(
                        "grant_type", "refresh_token",
                        "refresh_token", refreshToken,
                        "client_id", clientId));
                TokenStore.Tokens n = new TokenStore.Tokens();
                n.clientId = clientId;
                n.refreshToken = refreshToken;
                store(n, o);
                return n.accessToken;
            } catch (Exception e) {
                if (String.valueOf(e.getMessage()).toLowerCase().contains("revoked")
                        || String.valueOf(e.getMessage()).contains("invalid_grant")) {
                    TokenStore.clear();
                    TunewireClient.chat("Spotify access was revoked. Connect again from Player → Settings.", true);
                }
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        return refreshing;
    }

    public static void logout() {
        cancelLogin();
        TokenStore.clear();
        SpotifyPlayer.INSTANCE.reset();
    }

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static final class NotLoggedIn extends RuntimeException {
        public NotLoggedIn() {
            super("Not connected to Spotify");
        }
    }
}
