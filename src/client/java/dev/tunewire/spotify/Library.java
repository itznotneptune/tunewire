package dev.tunewire.spotify;

import dev.tunewire.core.Collection;
import dev.tunewire.core.Http;
import dev.tunewire.core.Json;
import dev.tunewire.core.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The user's library: their playlists, saved albums and Liked Songs, plus the one collection currently
 * opened in the player. Lists load a page at a time (50, Spotify's maximum) as the user scrolls.
 *
 * Since February 2026 a Development Mode app may list the songs of a playlist only when the user owns or
 * collaborates on it; other playlists can still be played as a whole.
 */
public final class Library {
    public static final Library INSTANCE = new Library();
    private static final int PAGE = 50;

    /** A list being loaded page by page. Replaced as a whole, so readers on the render thread see a consistent view. */
    public record ListState<T>(List<T> items, int total, boolean loading, boolean hasMore, String error) {
        static <T> ListState<T> empty() {
            return new ListState<>(List.of(), -1, false, true, "");
        }

        ListState<T> asLoading() {
            return new ListState<>(items, total, true, hasMore, "");
        }

        boolean loaded() {
            return total >= 0;
        }
    }

    private volatile ListState<Collection> playlists = ListState.empty();
    private volatile ListState<Collection> albums = ListState.empty();
    private volatile int likedTotal = -1;
    private volatile String userId = "";

    private volatile Collection open;
    private volatile ListState<Track> openTracks = ListState.empty();
    /** Why the open collection's songs can't be listed (e.g. someone else's playlist); empty otherwise. */
    private volatile String openNote = "";
    private int openSeq;

    private Library() {}

    public ListState<Collection> playlists() {
        return playlists;
    }

    public ListState<Collection> albums() {
        return albums;
    }

    public int likedTotal() {
        return likedTotal;
    }

    public Collection open() {
        return open;
    }

    public ListState<Track> openTracks() {
        return openTracks;
    }

    public String openNote() {
        return openNote;
    }

    public void reset() {
        playlists = ListState.empty();
        albums = ListState.empty();
        likedTotal = -1;
        userId = "";
        close();
    }

    /** Loads the first page of playlists and albums, the Liked Songs count and the user id, once. */
    public void ensureRoot() {
        if (!playlists.loaded() && !playlists.loading()) morePlaylists();
        if (!albums.loaded() && !albums.loading()) moreAlbums();
        if (likedTotal < 0) {
            likedTotal = 0; // in flight; avoids re-requesting every frame
            SpotifyApi.get("/me/tracks?limit=1").thenAccept(r -> {
                if (r.ok()) likedTotal = (int) Json.lng(r.json(), "total", 0);
            });
        }
        if (userId.isEmpty()) {
            userId = "?";
            SpotifyApi.get("/me").thenAccept(r -> userId = r.ok() ? Json.str(r.json(), "id") : "");
        }
    }

    public void refresh() {
        playlists = ListState.empty();
        albums = ListState.empty();
        likedTotal = -1;
        ensureRoot();
    }

    public void morePlaylists() {
        ListState<Collection> s = playlists;
        if (s.loading() || s.loaded() && !s.hasMore()) return;
        playlists = s.asLoading();
        SpotifyApi.get("/me/playlists?limit=" + PAGE + "&offset=" + s.items().size()).whenComplete((r, ex) ->
                playlists = merge(s, r, ex, page -> Collection.parsePage(page, Collection.Kind.PLAYLIST)));
    }

    public void moreAlbums() {
        ListState<Collection> s = albums;
        if (s.loading() || s.loaded() && !s.hasMore()) return;
        albums = s.asLoading();
        SpotifyApi.get("/me/albums?limit=" + PAGE + "&offset=" + s.items().size()).whenComplete((r, ex) ->
                albums = merge(s, r, ex, page -> Collection.parsePage(page, Collection.Kind.ALBUM)));
    }

    /** Whether the user can list this playlist's songs (owner, or collaborative), as far as we know. */
    public boolean canListSongs(Collection c) {
        if (c.kind() != Collection.Kind.PLAYLIST) return true;
        String me = userId;
        return me.isEmpty() || me.equals("?") || me.equals(c.ownerId());
    }

    // ------------------------------------------------------------------ open collection

    public synchronized void openCollection(Collection c) {
        open = c;
        openSeq++;
        openNote = "";
        if (c.kind() == Collection.Kind.ALBUM && !c.embedded().isEmpty()) {
            boolean more = c.total() > c.embedded().size();
            openTracks = new ListState<>(c.embedded(), c.total(), false, more, "");
        } else {
            openTracks = ListState.empty();
            moreTracks();
        }
    }

    public synchronized void close() {
        open = null;
        openSeq++;
        openTracks = ListState.empty();
        openNote = "";
    }

    public synchronized void moreTracks() {
        Collection c = open;
        ListState<Track> s = openTracks;
        if (c == null || s.loading() || s.loaded() && !s.hasMore()) return;
        int offset = s.items().size();
        String path = switch (c.kind()) {
            case LIKED -> "/me/tracks?limit=" + PAGE + "&offset=" + offset;
            case PLAYLIST -> "/playlists/" + Http.enc(c.id()) + "/items?limit=" + PAGE + "&offset=" + offset
                    + "&additional_types=track,episode";
            case ALBUM -> "/albums/" + Http.enc(c.id()) + "/tracks?limit=" + PAGE + "&offset=" + offset;
        };
        int seq = openSeq;
        openTracks = s.asLoading();
        SpotifyApi.get(path).whenComplete((r, ex) -> {
            synchronized (this) {
                if (seq != openSeq) return; // user opened something else meanwhile
                if (ex == null && r != null && c.kind() == Collection.Kind.PLAYLIST && (r.status() == 403 || r.status() == 404)) {
                    openNote = "Spotify only lets apps list the songs of playlists you own or collaborate on. You can still play it.";
                    openTracks = new ListState<>(List.of(), 0, false, false, "");
                    return;
                }
                String albumName = c.kind() == Collection.Kind.ALBUM ? c.name() : null;
                openTracks = merge(s, r, ex, page -> Collection.Page.tracks(page, albumName, c.imageUrl()));
                if (c.kind() == Collection.Kind.PLAYLIST && openTracks.items().isEmpty() && openTracks.error().isEmpty()
                        && c.total() > 0) {
                    openNote = "Spotify only lets apps list the songs of playlists you own or collaborate on. You can still play it.";
                }
            }
        });
    }

    // ------------------------------------------------------------------ search

    /** Playlist or album search (Spotify caps Development Mode search at 10 results). */
    public CompletableFuture<List<Collection>> search(String query, Collection.Kind kind) {
        String type = kind == Collection.Kind.ALBUM ? "album" : "playlist";
        return SpotifyApi.get("/search?type=" + type + "&limit=10&q=" + Http.enc(query)).thenApply(r -> {
            if (!r.ok()) throw new SpotifyApi.ApiException(r);
            return Collection.parsePage(Json.obj(r.json(), type + "s"), kind).items();
        });
    }

    // ------------------------------------------------------------------ helpers

    private interface PageParser<T> {
        Collection.Page<T> parse(com.google.gson.JsonObject page);
    }

    private static <T> ListState<T> merge(ListState<T> before, SpotifyApi.Response r, Throwable ex, PageParser<T> parser) {
        if (ex != null) {
            Throwable c = SpotifyApi.cause(ex);
            // hasMore=false: auto-paging must not retry every frame after a failure; Refresh starts over
            return new ListState<>(before.items(), Math.max(0, before.total()), false, false,
                    c.getMessage() == null ? c.toString() : c.getMessage());
        }
        if (!r.ok()) {
            String err = r.status() == 403 ? "Spotify refused (" + r.errorText() + "). Reconnect in Settings to grant library access."
                    : r.errorText();
            return new ListState<>(before.items(), Math.max(0, before.total()), false, false, err);
        }
        try {
            Collection.Page<T> page = parser.parse(r.json());
            List<T> all = new ArrayList<>(before.items());
            all.addAll(page.items());
            return new ListState<>(List.copyOf(all), page.total(), false, page.hasNext(), "");
        } catch (Exception e) {
            return new ListState<>(before.items(), Math.max(0, before.total()), false, false, "Unexpected reply from Spotify");
        }
    }
}
