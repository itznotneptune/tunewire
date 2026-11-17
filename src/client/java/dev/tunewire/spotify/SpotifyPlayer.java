package dev.tunewire.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.tunewire.TunewireClient;
import dev.tunewire.art.CoverArt;
import dev.tunewire.config.TokenStore;
import dev.tunewire.core.Http;
import dev.tunewire.core.Json;
import dev.tunewire.core.PlaybackState;
import dev.tunewire.core.Track;
import dev.tunewire.gui.PlayerScreen;
import dev.tunewire.lyrics.LyricsService;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * What Spotify is playing, and the remote-control actions. Polls GET /v1/me/player and extrapolates
 * the position between polls. Actions update the local state immediately; a poll that was already in
 * flight when an action was sent is discarded so the UI does not flicker back.
 */
public final class SpotifyPlayer {
    public static final SpotifyPlayer INSTANCE = new SpotifyPlayer();

    public record Device(String id, String name, String type, boolean active, int volume) {}

    private volatile PlaybackState state;
    private volatile boolean everPolled;
    private volatile String status = "";
    private volatile long statusAt;
    private volatile long nextPollAt;
    private volatile boolean polling;
    private volatile String lastTrackUri = "";
    private volatile List<Device> devices = List.of();
    private final AtomicInteger actionSeq = new AtomicInteger();

    private volatile List<Track> queue = List.of();
    /** Track URI the queue was last fetched during, and when; drives refreshes. */
    private volatile String queueFor = "";
    private volatile long queueAt;
    private volatile boolean queueLoading;
    private volatile boolean queueWanted;

    private volatile int pendingVolume = -1;
    private volatile long lastVolumeSent;

    private SpotifyPlayer() {}

    // ------------------------------------------------------------------ state

    /** Latest snapshot, or null when no Spotify device is playing anything. */
    public PlaybackState state() {
        return state;
    }

    public Track track() {
        PlaybackState s = state;
        return s == null ? null : s.track();
    }

    public long position() {
        PlaybackState s = state;
        return s == null ? 0 : s.positionAt(System.currentTimeMillis());
    }

    public boolean everPolled() {
        return everPolled;
    }

    public List<Device> devices() {
        return devices;
    }

    /** Recent status or error text, empty when it is older than {@code maxAgeMs}. */
    public String status(long maxAgeMs) {
        return System.currentTimeMillis() - statusAt <= maxAgeMs ? status : "";
    }

    private void setStatus(String s) {
        status = s;
        statusAt = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof PlayerScreen)) TunewireClient.actionBar(s);
    }

    /** Upcoming items, in play order (Spotify includes autoplay/context tracks, not just manual queue adds). */
    public List<Track> queue() {
        return queue;
    }

    public boolean queueLoading() {
        return queueLoading;
    }

    /** The item after the current one, if the queue was fetched during this track. */
    public Track nextUp() {
        Track t = track();
        List<Track> q = queue;
        return t != null && t.uri().equals(queueFor) && !q.isEmpty() ? q.get(0) : null;
    }

    /** The Queue tab is visible: keep the list fresh. */
    public void wantQueue() {
        queueWanted = true;
    }

    public void refreshQueue() {
        if (queueLoading) return;
        queueLoading = true;
        Track t = track();
        String forUri = t == null ? "" : t.uri();
        SpotifyApi.get("/me/player/queue").whenComplete((r, ex) -> {
            queueLoading = false;
            queueAt = System.currentTimeMillis();
            if (ex != null || r == null || !r.ok()) return; // keep the old list; the tab shows it
            queue = List.copyOf(Track.parseQueue(r.json()));
            queueFor = forUri;
        });
    }

    private void maybeRefreshQueue(long now) {
        boolean wanted = queueWanted;
        queueWanted = false;
        PlaybackState s = state;
        if (s == null || s.track() == null || queueLoading || SpotifyApi.backingOff()) return;
        boolean stale = !s.track().uri().equals(queueFor);
        if (wanted) {
            if (stale || now - queueAt > 8000) refreshQueue();
            return;
        }
        // for the HUD's "Next up": fetch once per track as the end approaches
        long left = s.track().durationMs() - s.positionAt(now);
        if (stale && s.playing() && left <= 25_000 && now - queueAt > 3000) refreshQueue();
    }

    public void reset() {
        state = null;
        everPolled = false;
        devices = List.of();
        queue = List.of();
        queueFor = "";
        Library.INSTANCE.reset();
        lastTrackUri = "";
        LyricsService.INSTANCE.clear();
        CoverArt.INSTANCE.clear();
    }

    // ------------------------------------------------------------------ polling

    public void pollSoon(long delayMs) {
        nextPollAt = Math.min(nextPollAt, System.currentTimeMillis() + delayMs);
    }

    public void tick(Minecraft mc) {
        if (!TokenStore.hasLogin()) return;
        long now = System.currentTimeMillis();
        flushVolume(now);
        boolean screenOpen = mc.screen instanceof PlayerScreen;
        windowActive = mc.isWindowActive();
        if (mc.level == null && !screenOpen) return; // HUD is not visible: don't spend requests
        maybeRefreshQueue(now);
        if (polling || now < nextPollAt || SpotifyApi.backingOff()) return;
        poll(screenOpen);
    }

    private void poll(boolean screenOpen) {
        polling = true;
        int seq = actionSeq.get();
        SpotifyApi.get("/me/player?additional_types=episode").whenComplete((r, ex) -> {
            polling = false;
            long now = System.currentTimeMillis();
            if (ex != null) {
                Throwable c = SpotifyApi.cause(ex);
                if (!(c instanceof SpotifyAuth.NotLoggedIn)) {
                    status = c.getMessage() == null ? c.toString() : c.getMessage();
                    statusAt = now;
                }
                nextPollAt = now + 5000;
                return;
            }
            if (seq != actionSeq.get()) {
                nextPollAt = now + 300; // an action raced this poll; ask again for the post-action state
                return;
            }
            if (r.status() == 204 || (r.ok() && r.json() == null)) {
                apply(null);
            } else if (r.ok()) {
                apply(PlaybackState.parse(r.json(), now));
            } else if (r.status() == 429) {
                status = SpotifyApi.backoffMessage();
                statusAt = now;
            } else {
                status = r.errorText();
                statusAt = now;
            }
            everPolled = true;
            nextPollAt = now + interval(screenOpen);
        });
    }

    /** Whether the game window has focus; an unfocused game polls slowly. */
    private volatile boolean windowActive = true;

    /**
     * How long until the next playback poll. Kept well inside Spotify's rate limit (a rolling 30 s window per app):
     * the position is interpolated locally between polls, so the bar and lyrics stay smooth at these rates.
     */
    private long interval(boolean screenOpen) {
        PlaybackState s = state;
        boolean playing = s != null && s.playing();
        long base = screenOpen ? 2000 : playing ? 4000 : 10_000;
        if (!windowActive) base = Math.max(base, 10_000);
        if (playing && s.track() != null && s.track().durationMs() > 0) {
            long left = s.track().durationMs() - s.positionAt(System.currentTimeMillis());
            if (left >= 0 && left < base) return Math.max(1000, left + 500); // catch the track change promptly
        }
        return base;
    }

    private void apply(PlaybackState s) {
        state = PlaybackState.keepTimeline(state, s);
        if (s != null) rememberDevice(s.deviceId(), s.deviceName());
        String uri = s == null || s.track() == null ? "" : s.track().uri();
        if (!uri.equals(lastTrackUri)) {
            lastTrackUri = uri;
            Track t = s == null ? null : s.track();
            // once per track change, so a skip that lands on another explicit song skips that too, but never loops on one
            if (t != null && t.explicit() && s.playing() && dev.tunewire.config.TwConfig.get().skipExplicit) {
                setStatus("Skipped explicit song: " + t.title());
                next();
            }
            LyricsService.INSTANCE.request(t);
            CoverArt.INSTANCE.request(t == null ? "" : t.imageUrl());
        }
    }

    /** Keeps the device as the commands' target, saved so it survives a restart; only written when it changes. */
    private static void rememberDevice(String id, String name) {
        if (id == null || id.isEmpty()) return;
        dev.tunewire.config.TwConfig c = dev.tunewire.config.TwConfig.get();
        if (id.equals(c.lastDeviceId) && java.util.Objects.equals(name, c.lastDeviceName)) return;
        c.lastDeviceId = id;
        c.lastDeviceName = name == null ? "" : name;
        dev.tunewire.config.TwConfig.save();
    }

    /** The remembered device's name, or "" when none is known yet. */
    public String deviceName() {
        return dev.tunewire.config.TwConfig.get().lastDeviceName;
    }

    /**
     * {@code path} sent to the remembered device ({@code device_id}), so a command reaches it even after Spotify stopped
     * calling it active, instead of failing with NO_ACTIVE_DEVICE. Unchanged when no device is known yet.
     */
    private static String toDevice(String path) {
        String id = dev.tunewire.config.TwConfig.get().lastDeviceId;
        if (id == null || id.isEmpty()) return path;
        return path + (path.contains("?") ? "&" : "?") + "device_id=" + Http.enc(id);
    }

    /** Local edit after an action; bumps the sequence so stale in-flight polls are dropped. */
    private void local(java.util.function.UnaryOperator<PlaybackState> edit) {
        actionSeq.incrementAndGet();
        PlaybackState s = state;
        if (s != null) state = edit.apply(s);
    }

    // ------------------------------------------------------------------ actions

    public void togglePlay() {
        PlaybackState s = state;
        if (s != null && s.playing()) {
            local(p -> p.withPlaying(false, System.currentTimeMillis()));
            act(() -> SpotifyApi.put(toDevice("/me/player/pause"), null), true);
        } else {
            local(p -> p.withPlaying(true, System.currentTimeMillis()));
            act(() -> SpotifyApi.put(toDevice("/me/player/play"), null), true);
        }
    }

    public void next() {
        actionSeq.incrementAndGet();
        act(() -> SpotifyApi.post(toDevice("/me/player/next"), null), true);
    }

    /** Like the Spotify app: restart the track when more than 3 s in, otherwise go back one. */
    public void previous() {
        if (position() > 3000) {
            seek(0);
            return;
        }
        actionSeq.incrementAndGet();
        act(() -> SpotifyApi.post(toDevice("/me/player/previous"), null), true);
    }

    public void seek(long ms) {
        long target = Math.max(0, ms);
        local(p -> p.withProgress(target, System.currentTimeMillis()));
        act(() -> SpotifyApi.put(toDevice("/me/player/seek?position_ms=" + target), null), true);
    }

    public void seekBy(long deltaMs) {
        seek(position() + deltaMs);
    }

    /** Sets volume locally at once; the request is throttled so dragging the slider doesn't flood Spotify. */
    public void setVolume(int percent) {
        int v = Math.max(0, Math.min(100, percent));
        local(p -> p.withVolume(v));
        pendingVolume = v;
    }

    public void changeVolume(int delta) {
        PlaybackState s = state;
        if (s == null || s.volume() < 0) {
            setStatus("Volume is not available on this device");
            return;
        }
        setVolume(s.volume() + delta);
        setStatus("Volume " + Math.max(0, Math.min(100, s.volume() + delta)) + "%");
    }

    private void flushVolume(long now) {
        int v = pendingVolume;
        if (v < 0 || now - lastVolumeSent < 250) return;
        pendingVolume = -1;
        lastVolumeSent = now;
        act(() -> SpotifyApi.put(toDevice("/me/player/volume?volume_percent=" + v), null), false);
    }

    public void toggleShuffle() {
        PlaybackState s = state;
        boolean on = s == null || !s.shuffle();
        local(p -> p.withShuffle(on));
        act(() -> SpotifyApi.put(toDevice("/me/player/shuffle?state=" + on), null), true);
    }

    public void cycleRepeat() {
        PlaybackState s = state;
        String next = PlaybackState.nextRepeat(s == null ? "off" : s.repeat());
        local(p -> p.withRepeat(next));
        act(() -> SpotifyApi.put(toDevice("/me/player/repeat?state=" + next), null), true);
    }

    public void playTrack(Track t) {
        actionSeq.incrementAndGet();
        String body = "{\"uris\":[\"" + t.uri().replace("\"", "") + "\"]}";
        act(() -> SpotifyApi.put(toDevice("/me/player/play"), body), true);
        setStatus("Playing " + t.title());
    }

    /**
     * Plays a playlist or album as its context (so Spotify carries on through it), starting at {@code start}
     * when given. Liked Songs has no playable context URI in the Web API, so its loaded tracks are sent as a list.
     */
    public void playCollection(dev.tunewire.core.Collection c, List<Track> loaded, Track start) {
        actionSeq.incrementAndGet();
        String body;
        if (c.kind() == dev.tunewire.core.Collection.Kind.LIKED) {
            if (loaded.isEmpty()) return;
            int idx = start == null ? 0 : Math.max(0, loaded.indexOf(start));
            // send a window of up to 100 around the chosen song, Spotify's limit for a URI list
            int from = Math.max(0, Math.min(idx, loaded.size() - 100)), to = Math.min(loaded.size(), from + 100);
            StringBuilder uris = new StringBuilder();
            for (int i = from; i < to; i++) {
                if (uris.length() > 0) uris.append(',');
                uris.append('"').append(loaded.get(i).uri().replace("\"", "")).append('"');
            }
            body = "{\"uris\":[" + uris + "],\"offset\":{\"position\":" + (idx - from) + "}}";
        } else {
            String ctx = "\"context_uri\":\"" + c.uri().replace("\"", "") + "\"";
            body = start == null ? "{" + ctx + "}"
                    : "{" + ctx + ",\"offset\":{\"uri\":\"" + start.uri().replace("\"", "") + "\"}}";
        }
        String b = body;
        act(() -> SpotifyApi.put(toDevice("/me/player/play"), b), true);
        setStatus("Playing " + (start != null ? start.title() : c.name()));
    }

    public void queue(Track t) {
        act(() -> SpotifyApi.post(toDevice("/me/player/queue?uri=" + Http.enc(t.uri())), null), true)
                .thenAccept(ok -> {
                    if (ok) {
                        setStatus("Added to queue: " + t.title());
                        queueAt = 0;
                        queueFor = ""; // refetch so the Queue tab and "Next up" include it
                    }
                });
    }

    public CompletableFuture<List<Track>> search(String query) {
        // Development Mode apps may ask for at most 10 results (February 2026 Web API change)
        return SpotifyApi.get("/search?type=track&limit=10&q=" + Http.enc(query)).thenApply(r -> {
            if (!r.ok()) throw new SpotifyApi.ApiException(r);
            return Track.parseSearch(r.json());
        });
    }

    public CompletableFuture<List<Device>> refreshDevices() {
        return SpotifyApi.get("/me/player/devices").thenApply(r -> {
            if (!r.ok()) throw new SpotifyApi.ApiException(r);
            List<Device> out = new ArrayList<>();
            JsonArray arr = Json.arr(r.json(), "devices");
            if (arr != null) for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject d = e.getAsJsonObject();
                if (Json.bool(d, "is_restricted")) continue;
                out.add(new Device(Json.str(d, "id"), Json.str(d, "name"), Json.str(d, "type"),
                        Json.bool(d, "is_active"), (int) Json.lng(d, "volume_percent", -1)));
            }
            devices = List.copyOf(out);
            return devices;
        });
    }

    public void transferTo(Device d) {
        actionSeq.incrementAndGet();
        String body = "{\"device_ids\":[\"" + d.id().replace("\"", "") + "\"],\"play\":true}";
        act(() -> SpotifyApi.put("/me/player", body), false).thenAccept(ok -> {
            if (ok) {
                rememberDevice(d.id(), d.name());
                setStatus("Playing on " + d.name());
            }
        });
    }

    /**
     * Runs an action and reports failures. On NO_ACTIVE_DEVICE it wakes a device and retries once: the remembered
     * device first, directly (one request, no device-list lookup); only if that fails, the device list is read and the
     * remembered device, else the first computer, else the first device listed, is used. Completes with whether it
     * succeeded.
     */
    private CompletableFuture<Boolean> act(Supplier<CompletableFuture<SpotifyApi.Response>> call, boolean wakeDevice) {
        return call.get().thenCompose(r -> {
            if (r.ok()) return CompletableFuture.completedFuture(true);
            if (wakeDevice && r.status() == 404 && "NO_ACTIVE_DEVICE".equals(r.errorText())) {
                String remembered = dev.tunewire.config.TwConfig.get().lastDeviceId;
                CompletableFuture<Boolean> woke = remembered.isEmpty() ? CompletableFuture.completedFuture(false)
                        : wake(remembered);
                return woke.thenCompose(ok -> ok ? retry(call) : refreshDevices().thenCompose(list -> {
                    Device pick = list.stream().filter(d -> d.id().equals(remembered)).findFirst()
                            .or(() -> list.stream().filter(d -> "Computer".equalsIgnoreCase(d.type())).findFirst())
                            .orElse(list.isEmpty() ? null : list.get(0));
                    if (pick == null) {
                        report("No Spotify device found. Open Spotify on this PC or your phone first.");
                        return CompletableFuture.completedFuture(false);
                    }
                    rememberDevice(pick.id(), pick.name());
                    return wake(pick.id()).thenCompose(w -> retry(call));
                }));
            }
            report(describe(r));
            return CompletableFuture.completedFuture(false);
        }).exceptionally(ex -> {
            Throwable c = SpotifyApi.cause(ex);
            report(c instanceof SpotifyAuth.NotLoggedIn ? "Not connected to Spotify. Open the player (Settings tab) to connect."
                    : c.getMessage() == null ? c.toString() : c.getMessage());
            return false;
        }).whenComplete((ok, ex) -> pollSoon(350));
    }

    /** Makes {@code deviceId} the active device without starting playback. Completes with whether Spotify accepted. */
    private static CompletableFuture<Boolean> wake(String deviceId) {
        String body = "{\"device_ids\":[\"" + deviceId.replace("\"", "") + "\"],\"play\":false}";
        return SpotifyApi.put("/me/player", body).thenApply(SpotifyApi.Response::ok).exceptionally(ex -> false);
    }

    /** Sends the action again after a device was woken, reporting a second failure. */
    private CompletableFuture<Boolean> retry(Supplier<CompletableFuture<SpotifyApi.Response>> call) {
        return call.get().thenApply(again -> {
            if (!again.ok()) report(describe(again));
            return again.ok();
        });
    }

    private void report(String msg) {
        setStatus(msg);
        pollSoon(0);
    }

    private static String describe(SpotifyApi.Response r) {
        String e = r.errorText();
        return switch (e) {
            case "PREMIUM_REQUIRED" -> "Spotify Premium is required for playback control.";
            case "NO_ACTIVE_DEVICE" -> "No active Spotify device. Start playback in Spotify once.";
            case "UNKNOWN" -> "Spotify couldn't do that right now.";
            default -> r.status() == 403 ? "Spotify refused: " + e : e;
        };
    }
}
