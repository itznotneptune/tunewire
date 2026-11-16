package dev.tunewire.core;

import com.google.gson.JsonObject;

/** One snapshot of GET /v1/me/player. {@code fetchedAt} is the local clock when it arrived. */
public record PlaybackState(Track track, boolean playing, long progressMs, String deviceId, String deviceName,
                            int volume, boolean supportsVolume, boolean shuffle, String repeat, long fetchedAt) {

    /** Parses the player object; null if nothing usable is playing (e.g. an ad, or no item). */
    public static PlaybackState parse(JsonObject root, long now) {
        if (root == null) return null;
        Track track = Track.parse(Json.obj(root, "item"));
        JsonObject device = Json.obj(root, "device");
        int volume = device == null ? -1 : (int) Json.lng(device, "volume_percent", -1);
        boolean supportsVolume = device != null && (device.has("supports_volume") ? Json.bool(device, "supports_volume") : volume >= 0);
        String repeat = Json.str(root, "repeat_state");
        return new PlaybackState(track, Json.bool(root, "is_playing"), Json.lng(root, "progress_ms", 0),
                device == null ? "" : Json.str(device, "id"), device == null ? "" : Json.str(device, "name"),
                volume, supportsVolume, Json.bool(root, "shuffle_state"), repeat.isEmpty() ? "off" : repeat, now);
    }

    /** Playback position now, extrapolated from the snapshot while playing. */
    public long positionAt(long now) {
        long p = playing ? progressMs + Math.max(0, now - fetchedAt) : progressMs;
        long dur = track == null ? 0 : track.durationMs();
        return dur > 0 ? Math.min(p, dur) : p;
    }

    public PlaybackState withPlaying(boolean p, long now) {
        return new PlaybackState(track, p, positionAt(now), deviceId, deviceName, volume, supportsVolume, shuffle, repeat, now);
    }

    public PlaybackState withProgress(long ms, long now) {
        return new PlaybackState(track, playing, ms, deviceId, deviceName, volume, supportsVolume, shuffle, repeat, now);
    }

    public PlaybackState withVolume(int v) {
        return new PlaybackState(track, playing, progressMs, deviceId, deviceName, v, supportsVolume, shuffle, repeat, fetchedAt);
    }

    public PlaybackState withShuffle(boolean s) {
        return new PlaybackState(track, playing, progressMs, deviceId, deviceName, volume, supportsVolume, s, repeat, fetchedAt);
    }

    public PlaybackState withRepeat(String r) {
        return new PlaybackState(track, playing, progressMs, deviceId, deviceName, volume, supportsVolume, shuffle, r, fetchedAt);
    }

    /** Backward corrections at most this large are treated as latency, not a seek. */
    public static final long MAX_LATENCY_CORRECTION_MS = 1500;

    /**
     * A poll's progress arrives slightly behind (network latency, Spotify's own update rate). Taking it as is
     * makes the clock step backwards every poll, which flips the lyric line back and forth at line boundaries.
     * Small backward corrections are ignored; real seeks and forward corrections are taken.
     */
    public static PlaybackState keepTimeline(PlaybackState old, PlaybackState fresh) {
        if (old == null || fresh == null || old.track() == null || fresh.track() == null) return fresh;
        if (!old.track().uri().equals(fresh.track().uri()) || !old.playing() || !fresh.playing()) return fresh;
        long now = fresh.fetchedAt();
        long ours = old.positionAt(now), theirs = fresh.positionAt(now);
        long behind = ours - theirs;
        if (behind > 0 && behind <= MAX_LATENCY_CORRECTION_MS) return fresh.withProgress(ours, now);
        return fresh;
    }

    /** off → context → track → off, as the Spotify app cycles it. */
    public static String nextRepeat(String r) {
        return switch (r) {
            case "off" -> "context";
            case "context" -> "track";
            default -> "off";
        };
    }

    public static String formatTime(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }
}
