package dev.tunewire.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CensorTest {
    @Test
    void masksWordsKeepingFirstLetterAndCase() {
        assertEquals("What the f***", Censor.clean("What the fuck"));
        assertEquals("F****** right, s***!", Censor.clean("Fucking right, shit!"));
        assertEquals("b****** and m************", Censor.clean("bitches and motherfuckers"));
        assertEquals("f*****'", Censor.clean("fuckin'"));
    }

    @Test
    void wholeWordsOnly() {
        String clean = "A classic cocktail in class with Dickens, passing the assessment";
        assertSame(clean, Censor.clean(clean));
        assertEquals("kiss my a**", Censor.clean("kiss my ass"));
    }

    @Test
    void handlesEmpty() {
        assertNull(Censor.clean(null));
        assertEquals("", Censor.clean(""));
        assertEquals("♪", Censor.clean("♪"));
    }

    @Test
    void parsesExplicitFlag() {
        Track t = Track.parse(com.google.gson.JsonParser.parseString(
                "{\"type\":\"track\",\"uri\":\"spotify:track:x\",\"name\":\"X\",\"explicit\":true}").getAsJsonObject());
        assertTrue(t.explicit());
    }
}
