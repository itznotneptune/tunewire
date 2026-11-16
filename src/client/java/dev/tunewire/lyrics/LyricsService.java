package dev.tunewire.lyrics;

import com.google.gson.JsonParser;
import dev.tunewire.TunewireClient;
import dev.tunewire.core.Http;
import dev.tunewire.core.Lrc;
import dev.tunewire.core.LyricsMatch;
import dev.tunewire.core.LyricsMatch.Candidate;
import dev.tunewire.core.Track;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Lyrics from LRCLIB (lrclib.net, free, no key). Spotify has no public lyrics API. Looks up the exact
 * track first, then searches for other versions so the player can switch when one is out of sync.
 */
public final class LyricsService {
    public static final LyricsService INSTANCE = new LyricsService();

    public enum Status { NONE, LOADING, SYNCED, PLAIN, INSTRUMENTAL, NOT_FOUND, ERROR }

    /** What is shown for one track. Immutable; replaced as a whole. */
    public record Lyrics(String trackUri, Status status, List<Candidate> versions, int chosen, Lrc lrc, List<String> plain) {
        static Lyrics of(String uri, Status s) {
            return new Lyrics(uri, s, List.of(), -1, Lrc.EMPTY, List.of());
        }

        public Candidate current() {
            return chosen >= 0 && chosen < versions.size() ? versions.get(chosen) : null;
        }
    }

    private static final String BASE = "https://lrclib.net/api";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Map<String, Lyrics> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Lyrics> e) {
            return size() > 60;
        }
    };

    private volatile Lyrics current = Lyrics.of("", Status.NONE);

    private LyricsService() {}

    public Lyrics current() {
        return current;
    }

    public void clear() {
        current = Lyrics.of("", Status.NONE);
    }

    public void request(Track t) {
        if (t == null) {
            clear();
            return;
        }
        String uri = t.uri();
        synchronized (cache) {
            Lyrics hit = cache.get(uri);
            if (hit != null) {
                current = hit;
                return;
            }
        }
        current = Lyrics.of(uri, Status.LOADING);
        lookup(t).whenComplete((ly, ex) -> {
            Lyrics result = ex != null ? Lyrics.of(uri, Status.ERROR) : ly;
            if (ex != null) TunewireClient.LOG.warn("Lyrics lookup failed for {}", t.title(), ex);
            if (ex == null) synchronized (cache) {
                cache.put(uri, result);
            }
            if (current.trackUri().equals(uri)) current = result;
        });
    }

    /** Next usable version for the current track (wraps around). */
    public void nextVersion() {
        Lyrics l = current;
        if (l.versions().size() < 2) return;
        Lyrics n = choose(l.trackUri(), l.versions(), (l.chosen() + 1) % l.versions().size());
        synchronized (cache) {
            cache.put(l.trackUri(), n);
        }
        current = n;
    }

    private CompletableFuture<Lyrics> lookup(Track t) {
        double secs = t.durationMs() / 1000.0;
        String artist = t.primaryArtist();
        String exact = BASE + "/get?track_name=" + Http.enc(t.title()) + "&artist_name=" + Http.enc(artist)
                + "&album_name=" + Http.enc(t.album()) + "&duration=" + Math.round(secs);
        String search = BASE + "/search?track_name=" + Http.enc(LyricsMatch.cleanTitle(t.title()))
                + "&artist_name=" + Http.enc(artist);

        CompletableFuture<List<Candidate>> exactF = fetch(exact).thenApply(body -> {
            if (body == null) return List.<Candidate>of();
            return List.of(Candidate.parse(JsonParser.parseString(body).getAsJsonObject()));
        }).exceptionally(e -> List.of());
        CompletableFuture<List<Candidate>> searchF = fetch(search).thenApply(body -> {
            if (body == null) return List.<Candidate>of();
            return LyricsMatch.parseList(JsonParser.parseString(body).getAsJsonArray());
        });

        return exactF.thenCombine(searchF.exceptionally(e -> List.of()), (ex, se) -> {
            boolean instrumental = ex.stream().anyMatch(Candidate::instrumental);
            List<Candidate> all = new ArrayList<>(ex);
            all.addAll(se);
            // the exact match keeps first place among equals: rank() is a stable sort
            List<Candidate> ranked = LyricsMatch.rank(all, secs, artist);
            if (ranked.isEmpty()) return Lyrics.of(t.uri(), instrumental ? Status.INSTRUMENTAL : Status.NOT_FOUND);
            return choose(t.uri(), ranked, 0);
        });
    }

    private static Lyrics choose(String uri, List<Candidate> versions, int idx) {
        Candidate c = versions.get(idx);
        if (c.hasSynced()) {
            Lrc lrc = Lrc.parse(c.synced());
            if (!lrc.isEmpty()) return new Lyrics(uri, Status.SYNCED, versions, idx, lrc, List.of());
        }
        List<String> plain = c.hasPlain() ? List.of(c.plain().split("\\r?\\n")) : List.of();
        return new Lyrics(uri, plain.isEmpty() ? Status.NOT_FOUND : Status.PLAIN, versions, idx, Lrc.EMPTY, plain);
    }

    /** Body on 200, null on 404, exception otherwise. */
    private static CompletableFuture<String> fetch(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "Tunewire/" + TunewireClient.version() + " (Minecraft Fabric mod)")
                .GET().build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() == 404) return null;
            if (r.statusCode() != 200) throw new IllegalStateException("LRCLIB HTTP " + r.statusCode());
            return r.body();
        });
    }
}
