package dev.tunewire.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** OAuth 2.0 PKCE helpers (RFC 7636), as Spotify's Authorization Code with PKCE flow uses them. */
public final class Pkce {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private Pkce() {}

    /** 64 random bytes, base64url: 86 characters, inside the 43–128 the spec allows. */
    public static String verifier() {
        return randomToken(64);
    }

    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return URL.encodeToString(b);
    }

    /** S256 challenge: base64url(sha256(verifier)). */
    public static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL.encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
