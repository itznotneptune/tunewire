package dev.tunewire.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** A playlist, an album, or the user's Liked Songs: something that holds tracks and can be played as a whole. */
public record Collection(Kind kind, String id, String uri, String name, String subtitle, String ownerId,
                         String imageUrl, int total, List<Track> embedded) {
    public enum Kind { PLAYLIST, ALBUM, LIKED }

    public static Collection liked(int total) {
        return new Collection(Kind.LIKED, "", "", "Liked Songs", "Your saved songs", "", "", total, List.of());
    }

    /** A playlist object (simplified or full). Feb 2026 renamed {@code tracks} to {@code items}; both are read. */
    public static Collection parsePlaylist(JsonObject o) {
        if (o == null || Json.str(o, "uri").isEmpty()) return null;
        JsonObject owner = Json.obj(o, "owner");
        String ownerName = owner == null ? "" : Json.str(owner, "display_name");
        if (ownerName.isEmpty() && owner != null) ownerName = Json.str(owner, "id");
        JsonObject count = Json.obj(o, "items");
        if (count == null) count = Json.obj(o, "tracks");
        int total = count == null ? -1 : (int) Json.lng(count, "total", -1);
        return new Collection(Kind.PLAYLIST, Json.str(o, "id"), Json.str(o, "uri"), Json.str(o, "name"),
                ownerName.isEmpty() ? "Playlist" : "by " + ownerName, owner == null ? "" : Json.str(owner, "id"),
                Track.pickImage(Json.arr(o, "images"), 300), total, List.of());
    }

    /** An album object; tracks included in the response (saved albums carry the first page) are kept. */
    public static Collection parseAlbum(JsonObject o) {
        if (o == null || Json.str(o, "uri").isEmpty()) return null;
        List<String> artists = new ArrayList<>();
        JsonArray a = Json.arr(o, "artists");
        if (a != null) for (JsonElement e : a) if (e.isJsonObject()) artists.add(Json.str(e.getAsJsonObject(), "name"));
        artists.removeIf(String::isEmpty);
        String name = Json.str(o, "name");
        String image = Track.pickImage(Json.arr(o, "images"), 300);
        List<Track> embedded = List.of();
        JsonObject tracks = Json.obj(o, "tracks");
        if (tracks != null) embedded = parseTrackItems(Json.arr(tracks, "items"), name, image);
        return new Collection(Kind.ALBUM, Json.str(o, "id"), Json.str(o, "uri"), name,
                String.join(", ", artists), "", image, (int) Json.lng(o, "total_tracks", embedded.size()), embedded);
    }

    /** A paging object of playlists, saved albums ({@code items[].album}) or plain albums. */
    public static Page<Collection> parsePage(JsonObject page, Kind kind) {
        List<Collection> out = new ArrayList<>();
        JsonArray items = Json.arr(page, "items");
        if (items != null) for (JsonElement e : items) {
            if (!e.isJsonObject()) continue; // Spotify puts nulls in playlist search results
            JsonObject o = e.getAsJsonObject();
            Collection c;
            if (kind == Kind.ALBUM) {
                JsonObject inner = Json.obj(o, "album");
                c = parseAlbum(inner != null ? inner : o);
            } else {
                c = parsePlaylist(o);
            }
            if (c != null) out.add(c);
        }
        return Page.of(page, out);
    }

    /**
     * Track rows of a paging object: playlist items ({@code item}, formerly {@code track}), saved tracks
     * ({@code track}) or album tracks (the track itself, without album fields, so the album's are filled in).
     */
    public static List<Track> parseTrackItems(JsonArray items, String albumName, String albumImage) {
        List<Track> out = new ArrayList<>();
        if (items == null) return out;
        for (JsonElement e : items) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            JsonObject inner = Json.obj(o, "item");
            if (inner == null) inner = Json.obj(o, "track");
            Track t = Track.parse(inner != null ? inner : o);
            if (t == null) continue;
            if (albumName != null && t.album().isEmpty()) {
                t = new Track(t.id(), t.uri(), t.title(), t.artists(), albumName, t.durationMs(),
                        t.imageUrl().isEmpty() ? albumImage : t.imageUrl());
            }
            out.add(t);
        }
        return out;
    }

    /** One page of results plus where the list stands. */
    public record Page<T>(List<T> items, int total, boolean hasNext) {
        static <T> Page<T> of(JsonObject page, List<T> items) {
            String next = Json.str(page, "next");
            return new Page<>(items, (int) Json.lng(page, "total", items.size()), !next.isEmpty());
        }

        public static Page<Track> tracks(JsonObject page, String albumName, String albumImage) {
            return of(page, parseTrackItems(Json.arr(page, "items"), albumName, albumImage));
        }
    }
}
