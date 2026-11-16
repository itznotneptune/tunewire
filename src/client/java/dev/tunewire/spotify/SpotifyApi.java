package dev.tunewire.spotify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.tunewire.core.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Thin async wrapper over the Spotify Web API: auth header, one retry on 401, Retry-After on 429. */
public final class SpotifyApi {
    private static final String BASE = "https://api.spotify.com/v1";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Local clock before which nothing is sent, set from a 429's Retry-After. */
    private static volatile long backoffUntil;

    public record Response(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public JsonObject json() {
            try {
                return body == null || body.isBlank() ? null : JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) {
                return null;
            }
        }

        /** Spotify's {"error":{"status":..,"message":..,"reason":..}} as one readable string. */
        public String errorText() {
            JsonObject err = Json.obj(json(), "error");
            String reason = Json.str(err, "reason");
            String msg = Json.str(err, "message");
            if (!reason.isEmpty()) return reason;
            return msg.isEmpty() ? "HTTP " + status : msg;
        }
    }

    public static final class ApiException extends RuntimeException {
        public final Response response;

        public ApiException(Response r) {
            super(r.errorText());
            this.response = r;
        }
    }

    private SpotifyApi() {}

    public static boolean backingOff() {
        return System.currentTimeMillis() < backoffUntil;
    }

    public static CompletableFuture<Response> get(String path) {
        return call("GET", path, null);
    }

    public static CompletableFuture<Response> put(String path, String json) {
        return call("PUT", path, json);
    }

    public static CompletableFuture<Response> post(String path, String json) {
        return call("POST", path, json);
    }

    /** Sends a request; completes with any HTTP response (callers check status), or exceptionally on I/O or auth failure. */
    public static CompletableFuture<Response> call(String method, String path, String json) {
        if (backingOff()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Rate limited by Spotify, retrying shortly"));
        }
        return SpotifyAuth.accessToken(false)
                .thenCompose(token -> send(method, path, json, token))
                .thenCompose(r -> {
                    if (r.status() != 401) return CompletableFuture.completedFuture(r);
                    return SpotifyAuth.accessToken(true).thenCompose(token -> send(method, path, json, token));
                });
    }

    private static CompletableFuture<Response> send(String method, String path, String json, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : BASE + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token);
        if (json != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        } else if (method.equals("GET")) {
            b.GET();
        } else {
            // noBody() makes HttpClient send "Content-Length: 0", which Spotify wants on body-less PUT/POST
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            if (res.statusCode() == 429) {
                long secs = res.headers().firstValueAsLong("Retry-After").orElse(5);
                backoffUntil = System.currentTimeMillis() + Math.max(1, Math.min(120, secs)) * 1000;
            }
            return new Response(res.statusCode(), res.body());
        });
    }

    /** Unwraps CompletionException chains for messages. */
    public static Throwable cause(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
