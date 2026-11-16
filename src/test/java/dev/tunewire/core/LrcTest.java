package dev.tunewire.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LrcTest {
    @Test
    void parsesTimestampsAndFractions() {
        Lrc l = Lrc.parse("[ar:Someone]\n[00:01.5] one\n[00:02.25]two\n[01:03.123] three\n[00:04] four");
        assertEquals(4, l.lines().size());
        assertEquals(1500, l.lines().get(0).timeMs());
        assertEquals("one", l.lines().get(0).text());
        assertEquals(2250, l.lines().get(1).timeMs());
        assertEquals(4000, l.lines().get(2).timeMs());
        assertEquals(63_123, l.lines().get(3).timeMs());
    }

    @Test
    void repeatedTimestampsExpandAndSort() {
        Lrc l = Lrc.parse("[00:30.00][00:10.00] chorus\n[00:20.00] verse");
        assertEquals(3, l.lines().size());
        assertEquals("chorus", l.lines().get(0).text());
        assertEquals("verse", l.lines().get(1).text());
        assertEquals(30_000, l.lines().get(2).timeMs());
    }

    @Test
    void keepsEmptyLinesAsBreaks() {
        Lrc l = Lrc.parse("[00:01.00] a\n[00:05.00]\n[00:09.00] b");
        assertEquals("", l.lines().get(1).text());
    }

    @Test
    void offsetShiftsEarlier() {
        Lrc l = Lrc.parse("[offset:+500]\n[00:02.00] x");
        assertEquals(1500, l.lines().get(0).timeMs());
    }

    @Test
    void indexAtFindsCurrentLine() {
        Lrc l = Lrc.parse("[00:01.00] a\n[00:05.00] b\n[00:09.00] c");
        assertEquals(-1, l.indexAt(500));
        assertEquals(0, l.indexAt(1000));
        assertEquals(0, l.indexAt(4999));
        assertEquals(1, l.indexAt(5000));
        assertEquals(2, l.indexAt(60_000));
    }

    @Test
    void emptyAndGarbage() {
        assertTrue(Lrc.parse(null).isEmpty());
        assertTrue(Lrc.parse("no timestamps here").isEmpty());
        assertEquals(-1, Lrc.EMPTY.indexAt(1000));
    }

    @Test
    void timestampMidLineIsText() {
        Lrc l = Lrc.parse("[00:01.00] at [00:02.00] noon");
        assertEquals(1, l.lines().size());
        assertEquals("at [00:02.00] noon", l.lines().get(0).text());
    }
}
