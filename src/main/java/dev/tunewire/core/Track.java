package dev.tunewire.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** A Spotify track or episode, as the player and search endpoints return it. */
public record Track(String id, String uri, String title, List<String> artists, String album, long durationMs, String imageUrl,
                    boolean explicit) {
    public String artistLine() {
        return String.join(", ", artists);
    }

    public String primaryArtist() {
        return artists.isEmpty() ? "" : artists.get(0);
    }

    /** Parses a track or episode object; null when it is missing or unusable. */
    public static Track parse(JsonObject o) {
        if (o == null) return null;
        String uri = Json.str(o, "uri");
        if (uri.isEmpty()) return null;
        List<String> artists = new ArrayList<>();
        String album;
        JsonArray images;
        if ("episode".equals(Json.str(o, "type"))) {
            JsonObject show = Json.obj(o, "show");
            if (show != null) artists.add(Json.str(show, "name"));
            album = show == null ? "" : Json.str(show, "publisher");
            images = Json.arr(o, "images");
        } else {
            JsonArray a = Json.arr(o, "artists");
            if (a != null) for (JsonElement e : a) {
                if (e.isJsonObject()) artists.add(Json.str(e.getAsJsonObject(), "name"));
            }
            JsonObject alb = Json.obj(o, "album");
            album = alb == null ? "" : Json.str(alb, "name");
            images = alb == null ? null : Json.arr(alb, "images");
        }
        artists.removeIf(String::isEmpty);
        return new Track(Json.str(o, "id"), uri, Json.str(o, "name"), List.copyOf(artists), album,
                Json.lng(o, "duration_ms", 0), pickImage(images, 300), Json.bool(o, "explicit"));
    }

    /** The image whose width is closest to {@code target}; images without a width count as 300 (Spotify's medium). */
    static String pickImage(JsonArray images, int target) {
        if (images == null) return "";
        String best = "";
        long bestDiff = Long.MAX_VALUE;
        for (JsonElement e : images) {
            if (!e.isJsonObject()) continue;
            JsonObject img = e.getAsJsonObject();
            String url = Json.str(img, "url");
            if (url.isEmpty()) continue;
            long diff = Math.abs(Json.lng(img, "width", 300) - target);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = url;
            }
        }
        return best;
    }

    /** {@code queue} of a /v1/me/player/queue response: what plays after the current item, in order. */
    public static List<Track> parseQueue(JsonObject root) {
        List<Track> out = new ArrayList<>();
        JsonArray items = Json.arr(root, "queue");
        if (items == null) return out;
        for (JsonElement e : items) {
            if (!e.isJsonObject()) continue;
            Track t = parse(e.getAsJsonObject());
            if (t != null) out.add(t);
        }
        return out;
    }

    /** {@code tracks.items} of a /v1/search response. */
    public static List<Track> parseSearch(JsonObject root) {
        List<Track> out = new ArrayList<>();
        JsonObject tracks = Json.obj(root, "tracks");
        JsonArray items = tracks == null ? null : Json.arr(tracks, "items");
        if (items == null) return out;
        for (JsonElement e : items) {
            if (!e.isJsonObject()) continue;
            Track t = parse(e.getAsJsonObject());
            if (t != null) out.add(t);
        }
        return out;
    }
}
