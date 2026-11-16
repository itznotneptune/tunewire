package dev.tunewire.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollectionTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void playlistPageReadsNewAndOldCountFields() {
        var page = Collection.parsePage(json("""
                {"total":3,"next":"https://api.spotify.com/v1/me/playlists?offset=2","items":[
                  {"id":"p1","uri":"spotify:playlist:p1","name":"Mine","owner":{"id":"me","display_name":"Me"},
                   "images":[{"url":"img","width":300}],"items":{"total":12}},
                  null,
                  {"id":"p2","uri":"spotify:playlist:p2","name":"Old","owner":{"id":"x"},"tracks":{"total":5}}]}"""),
                Collection.Kind.PLAYLIST);
        assertEquals(2, page.items().size());
        assertTrue(page.hasNext());
        assertEquals(3, page.total());
        Collection mine = page.items().get(0);
        assertEquals("by Me", mine.subtitle());
        assertEquals("me", mine.ownerId());
        assertEquals(12, mine.total());
        assertEquals("img", mine.imageUrl());
        assertEquals(5, page.items().get(1).total());
        assertEquals("by x", page.items().get(1).subtitle());
    }

    @Test
    void savedAlbumsKeepEmbeddedTracksWithAlbumFilledIn() {
        var page = Collection.parsePage(json("""
                {"total":1,"next":null,"items":[{"added_at":"x","album":{
                  "id":"a","uri":"spotify:album:a","name":"Alb","total_tracks":2,"artists":[{"name":"Band"}],
                  "images":[{"url":"cov","width":300}],
                  "tracks":{"items":[{"type":"track","uri":"spotify:track:1","name":"One","artists":[{"name":"Band"}]}]}}}]}"""),
                Collection.Kind.ALBUM);
        Collection a = page.items().get(0);
        assertFalse(page.hasNext());
        assertEquals("Band", a.subtitle());
        assertEquals(2, a.total());
        assertEquals(1, a.embedded().size());
        assertEquals("Alb", a.embedded().get(0).album());
        assertEquals("cov", a.embedded().get(0).imageUrl());
    }

    @Test
    void trackItemsReadItemTrackOrPlain() {
        var items = JsonParser.parseString("""
                [{"added_at":"x","item":{"type":"track","uri":"spotify:track:new","name":"New"}},
                 {"added_at":"x","track":{"type":"track","uri":"spotify:track:old","name":"Old"}},
                 {"type":"track","uri":"spotify:track:plain","name":"Plain"},
                 {"added_at":"x","item":null}]""").getAsJsonArray();
        var tracks = Collection.parseTrackItems(items, null, null);
        assertEquals(3, tracks.size());
        assertEquals("New", tracks.get(0).title());
        assertEquals("Old", tracks.get(1).title());
        assertEquals("Plain", tracks.get(2).title());
    }
}
