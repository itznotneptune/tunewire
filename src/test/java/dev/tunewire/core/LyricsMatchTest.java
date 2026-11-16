package dev.tunewire.core;

import com.google.gson.JsonParser;
import dev.tunewire.core.LyricsMatch.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LyricsMatchTest {
    private static Candidate c(long id, String artist, double dur, String synced, String plain) {
        return new Candidate(id, "t", artist, "", dur, synced, plain, false);
    }

    @Test
    void cleansTitles() {
        assertEquals("Song", LyricsMatch.cleanTitle("Song - Remastered 2011"));
        assertEquals("Song", LyricsMatch.cleanTitle("Song (feat. Someone)"));
        assertEquals("Song", LyricsMatch.cleanTitle("Song [ft. X] - Live"));
        assertEquals("Song (Acoustic)", LyricsMatch.cleanTitle("Song (Acoustic)"));
        assertEquals("- Dash", LyricsMatch.cleanTitle("- Dash"));
    }

    @Test
    void ranksSyncedThenArtistThenDuration() {
        List<Candidate> r = LyricsMatch.rank(List.of(
                c(1, "Other", 200, null, "plain"),
                c(2, "Other", 201, "[00:01.00] x", null),
                c(3, "Band", 205, "[00:01.00] x", null),
                c(4, "Band", 200.5, "[00:01.00] x", null),
                c(5, "Band", 260, "[00:01.00] x", null),   // too far off
                c(6, "Band", 200, null, null),              // no lyrics
                c(4, "Band", 200.5, "[00:01.00] x", null)), // duplicate
                200, "band");
        assertEquals(List.of(4L, 3L, 2L, 1L), r.stream().map(Candidate::id).toList());
    }

    @Test
    void parsesLrclibJson() {
        var list = LyricsMatch.parseList(JsonParser.parseString("""
                [{"id":7,"trackName":"T","artistName":"A","albumName":"B","duration":180.5,
                  "instrumental":false,"plainLyrics":"x","syncedLyrics":"[00:01.00] x"},
                 {"id":8,"syncedLyrics":null,"plainLyrics":null,"instrumental":true}]""").getAsJsonArray());
        assertEquals(2, list.size());
        assertTrue(list.get(0).hasSynced());
        assertEquals(180.5, list.get(0).duration());
        assertFalse(list.get(1).hasSynced());
        assertTrue(list.get(1).instrumental());
    }
}
