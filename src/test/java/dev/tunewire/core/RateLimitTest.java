package dev.tunewire.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitTest {

    @Test
    void retryAfterIsHonouredInFullEvenWhenLong() {
        assertEquals(30, RateLimit.waitSeconds(30, 1));
        assertEquals(3600, RateLimit.waitSeconds(3600, 1), "no 120 s cap: asking early extends the block");
        assertEquals(RateLimit.MAX_SECONDS, RateLimit.waitSeconds(10_000_000, 1), "only a malformed value is capped");
    }

    @Test
    void aMissingRetryAfterUsesTheDefault() {
        assertEquals(RateLimit.DEFAULT_SECONDS, RateLimit.waitSeconds(-1, 1));
    }

    @Test
    void repeated429sBackOffExponentiallyUpToTenMinutes() {
        assertEquals(10, RateLimit.waitSeconds(1, 2));
        assertEquals(20, RateLimit.waitSeconds(1, 3));
        assertEquals(40, RateLimit.waitSeconds(-1, 4));
        assertEquals(RateLimit.MAX_ESCALATION_SECONDS, RateLimit.waitSeconds(1, 30));
        assertEquals(900, RateLimit.waitSeconds(900, 30), "a longer Retry-After still wins");
    }

    @Test
    void describeReadsNaturally() {
        assertEquals("45s", RateLimit.describe(45));
        assertEquals("3m 20s", RateLimit.describe(200));
        assertEquals("5m", RateLimit.describe(300));
        assertEquals("1h 5m", RateLimit.describe(3900));
        assertEquals("2h", RateLimit.describe(7200));
    }
}
