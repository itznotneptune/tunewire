package dev.tunewire.core;

/**
 * How long to stay quiet after Spotify answers 429 Too Many Requests. Spotify's Retry-After is always honoured in full
 * (Development Mode apps can be told to wait minutes or hours, and asking again early only extends the block). On top
 * of that, repeated 429s back off exponentially, so a client that keeps hitting the limit slows down even when Spotify
 * sends a short or missing Retry-After.
 */
public final class RateLimit {
    /** Used when Spotify sends no Retry-After. */
    public static final long DEFAULT_SECONDS = 5;
    /** The exponential floor stops growing here. */
    public static final long MAX_ESCALATION_SECONDS = 600;
    /** A sanity ceiling for a malformed Retry-After; Spotify's real values stay well below it. */
    public static final long MAX_SECONDS = 24 * 3600;

    private RateLimit() {}

    /**
     * @param retryAfterSeconds Spotify's Retry-After, or a negative number when absent
     * @param consecutive       how many 429s in a row this is, counting this one (1 for the first)
     * @return seconds to wait before sending anything again
     */
    public static long waitSeconds(long retryAfterSeconds, int consecutive) {
        long told = retryAfterSeconds < 0 ? DEFAULT_SECONDS : retryAfterSeconds;
        int n = Math.max(1, consecutive);
        // 5 s, 10 s, 20 s, 40 s ... up to 10 minutes for the 2nd, 3rd ... 429 in a row
        long escalation = n == 1 ? 0 : Math.min(MAX_ESCALATION_SECONDS, DEFAULT_SECONDS << Math.min(n - 1, 20));
        return Math.max(1, Math.min(MAX_SECONDS, Math.max(told, escalation)));
    }

    /** "45s", "3m 20s", "1h 5m": for the status line. */
    public static String describe(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + "s");
        long m = (seconds % 3600) / 60;
        return (seconds / 3600) + "h" + (m == 0 ? "" : " " + m + "m");
    }
}
