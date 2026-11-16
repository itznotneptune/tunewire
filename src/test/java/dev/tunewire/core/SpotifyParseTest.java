package dev.tunewire.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpotifyParseTest {
    private static final String PLAYER = """
            {"device":{"id":"dev1","name":"DESKTOP","volume_percent":42,"supports_volume":true},
             "shuffle_state":true,"repeat_state":"context","progress_ms":61000,"is_playing":true,
             "item":{"type":"track","id":"t1","uri":"spotify:track:t1","name":"Song","duration_ms":200000,
               "artists":[{"name":"A"},{"name":"B"}],
               "album":{"name":"Alb","images":[{"url":"big","width":640},{"url":"mid","width":300},{"url":"small","width":64}]}}}
            """;

    @Test
    void parsesPlayer() {
        PlaybackState s = PlaybackState.parse(JsonParser.parseString(PLAYER).getAsJsonObject(), 1000);
        assertNotNull(s);
        assertTrue(s.playing());
        assertEquals("Song", s.track().title());
        assertEquals("A, B", s.track().artistLine());
        assertEquals("A", s.track().primaryArtist());
        assertEquals("Alb", s.track().album());
        assertEquals("mid", s.track().imageUrl());
        assertEquals(42, s.volume());
        assertTrue(s.supportsVolume());
        assertTrue(s.shuffle());
        assertEquals("context", s.repeat());
        assertEquals("DESKTOP", s.deviceName());
    }

    @Test
    void positionExtrapolatesAndClamps() {
        PlaybackState s = PlaybackState.parse(JsonParser.parseString(PLAYER).getAsJsonObject(), 1000);
        assertEquals(61_000, s.positionAt(1000));
        assertEquals(63_500, s.positionAt(3500));
        assertEquals(200_000, s.positionAt(10_000_000));
        PlaybackState paused = s.withPlaying(false, 3000);
        assertEquals(63_000, paused.positionAt(99_999));
    }

    @Test
    void nullsAndMissingFields() {
        PlaybackState s = PlaybackState.parse(JsonParser.parseString("{\"is_playing\":false,\"item\":null,\"device\":null}").getAsJsonObject(), 0);
        assertNotNull(s);
        assertNull(s.track());
        assertEquals(-1, s.volume());
        assertFalse(s.supportsVolume());
        assertEquals("off", s.repeat());
    }

    @Test
    void parsesEpisode() {
        Track t = Track.parse(JsonParser.parseString("""
                {"type":"episode","id":"e","uri":"spotify:episode:e","name":"Ep 1","duration_ms":5,
                 "show":{"name":"Pod","publisher":"Pub"},"images":[{"url":"x","width":300}]}""").getAsJsonObject());
        assertEquals("Pod", t.artistLine());
        assertEquals("Pub", t.album());
        assertEquals("x", t.imageUrl());
    }

    @Test
    void parsesSearch() {
        List<Track> r = Track.parseSearch(JsonParser.parseString("""
                {"tracks":{"items":[{"type":"track","id":"1","uri":"spotify:track:1","name":"One","artists":[],"album":{"name":"X"}},
                                     null, {"name":"no uri"}]}}""").getAsJsonObject());
        assertEquals(1, r.size());
        assertEquals("One", r.get(0).title());
        assertEquals("", r.get(0).imageUrl());
    }

    @Test
    void parsesQueue() {
        List<Track> q = Track.parseQueue(JsonParser.parseString("""
                {"currently_playing":{"type":"track","uri":"spotify:track:now","name":"Now"},
                 "queue":[{"type":"track","uri":"spotify:track:a","name":"A","artists":[{"name":"X"}]},
                          {"type":"episode","uri":"spotify:episode:b","name":"B","show":{"name":"Pod"}}]}""").getAsJsonObject());
        assertEquals(List.of("A", "B"), q.stream().map(Track::title).toList());
        assertEquals("X", q.get(0).artistLine());
        assertTrue(Track.parseQueue(JsonParser.parseString("{}").getAsJsonObject()).isEmpty());
    }

    @Test
    void repeatCycleAndTime() {
        assertEquals("context", PlaybackState.nextRepeat("off"));
        assertEquals("track", PlaybackState.nextRepeat("context"));
        assertEquals("off", PlaybackState.nextRepeat("track"));
        assertEquals("0:00", PlaybackState.formatTime(-5));
        assertEquals("3:05", PlaybackState.formatTime(185_999));
        assertEquals("1:00:01", PlaybackState.formatTime(3_601_000));
    }
}
