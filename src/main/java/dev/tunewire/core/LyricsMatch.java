package dev.tunewire.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Picks and orders LRCLIB lyric versions for a track. */
public final class LyricsMatch {
    /** One LRCLIB record. {@code duration} is in seconds. */
    public record Candidate(long id, String track, String artist, String album, double duration,
                            String synced, String plain, boolean instrumental) {
        public boolean hasSynced() {
            return synced != null && !synced.isBlank();
        }

        public boolean hasPlain() {
            return plain != null && !plain.isBlank();
        }

        public static Candidate parse(JsonObject o) {
            return new Candidate(Json.lng(o, "id", 0), Json.str(o, "trackName"), Json.str(o, "artistName"),
                    Json.str(o, "albumName"), Json.dbl(o, "duration", 0), Json.str(o, "syncedLyrics"),
                    Json.str(o, "plainLyrics"), Json.bool(o, "instrumental"));
        }
    }

    private static final Pattern FEAT = Pattern.compile("\\s*[(\\[](feat\\.?|ft\\.?|with)\\s[^)\\]]*[)\\]]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DASH_SUFFIX = Pattern.compile("\\s+-\\s+.*$");

    private LyricsMatch() {}

    /** "Song - Remastered 2011 (feat. X)" → "Song": what lyric databases usually file it under. */
    public static String cleanTitle(String title) {
        String t = FEAT.matcher(title).replaceAll("");
        t = DASH_SUFFIX.matcher(t).replaceAll("");
        return t.strip().isEmpty() ? title.strip() : t.strip();
    }

    public static List<Candidate> parseList(JsonArray arr) {
        List<Candidate> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement e : arr) if (e.isJsonObject()) out.add(Candidate.parse(e.getAsJsonObject()));
        return out;
    }

    /**
     * Usable versions, best first: synced before plain-only, then closest in length to the track,
     * dropping versions more than 15 s off (almost always a different recording). Duplicate ids collapse.
     */
    public static List<Candidate> rank(List<Candidate> all, double trackSeconds, String artist) {
        String wantArtist = artist == null ? "" : artist.toLowerCase(Locale.ROOT);
        List<Candidate> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Candidate c : all) {
            if (!c.hasSynced() && !c.hasPlain()) continue;
            if (trackSeconds > 0 && c.duration() > 0 && Math.abs(c.duration() - trackSeconds) > 15) continue;
            if (!seen.add(c.id())) continue;
            out.add(c);
        }
        out.sort(Comparator.<Candidate>comparingInt(c -> c.hasSynced() ? 0 : 1)
                .thenComparingInt(c -> !wantArtist.isEmpty() && c.artist().toLowerCase(Locale.ROOT).contains(wantArtist) ? 0 : 1)
                .thenComparingDouble(c -> trackSeconds > 0 && c.duration() > 0 ? Math.abs(c.duration() - trackSeconds) : 99));
        return out;
    }
}
