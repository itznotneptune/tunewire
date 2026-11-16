package dev.tunewire.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthHelpersTest {
    @Test
    void pkceMatchesRfc7636Example() {
        // RFC 7636 appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void verifierLengthAndAlphabet() {
        String v = Pkce.verifier();
        assertTrue(v.length() >= 43 && v.length() <= 128, "length " + v.length());
        assertTrue(v.matches("[A-Za-z0-9_-]+"));
        assertNotEquals(v, Pkce.verifier());
    }

    @Test
    void queryAndRequestLine() {
        assertEquals("/callback?code=a%2Bb&state=s", Http.requestTarget("GET /callback?code=a%2Bb&state=s HTTP/1.1"));
        assertNull(Http.requestTarget("POST /x HTTP/1.1"));
        assertNull(Http.requestTarget("garbage"));
        Map<String, String> q = Http.parseQuery("code=a%2Bb&state=s&error=");
        assertEquals("a+b", q.get("code"));
        assertEquals("s", q.get("state"));
        assertEquals("", q.get("error"));
        Map<String, String> f = new LinkedHashMap<>();
        f.put("redirect_uri", "http://127.0.0.1:1/cb");
        f.put("x", "a b");
        assertEquals("redirect_uri=http%3A%2F%2F127.0.0.1%3A1%2Fcb&x=a%20b", Http.form(f));
    }
}
