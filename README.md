# Tunewire

A client-side Fabric mod for Minecraft 1.21.11 that puts your Spotify inside the game:

- **Now-playing HUD**: cover art, title and artist (they scroll if too long), and a progress bar. In the last 20 seconds of a song it shows **Next up:** with the following track.
- **Synced lyrics**: shown on the HUD and in the player, timed to the song. They come from [LRCLIB](https://lrclib.net), because Spotify has no public lyrics API.
  - Lines glide smoothly from one to the next, and long lines wrap instead of being cut off.
  - Three styles (Settings → Lyrics → Style):
    - **HUD box**: above the hotbar.
    - **Placed in world**: each line is set down where you're looking when it starts and stays there as you move.
    - **Floating**: 3D text that follows your view.

    For the two 3D styles you can set the distance, left/right and up/down offset, text size, and whether they show through blocks. While you adjust them, the world stays visible behind the settings.
  - **Explicit content** options: censor swear words in lyrics, hide explicit songs from lists, and skip explicit songs automatically. Explicit tracks always show an **E** badge.
  - Click a line to jump to it.
  - If the timing is off, switch to another lyric version or nudge the offset.
- **Player screen**:
  - seek bar, play/pause, next/previous, shuffle, repeat and volume;
  - a device picker. Tunewire remembers the device you last played on and sends every command to it, so it stays
    connected after Spotify stops calling it "active" (for example after a long pause);
  - a **Queue** tab listing what plays next;
  - scrolling lyrics;
  - two layouts, switched with the button next to fit-to-screen: **tabs** across the top, or an **app layout** with your library in a sidebar and a playback bar along the bottom;
  - a resizable window: drag the title bar to move it, drag the bottom-right corner to resize it, and use the **fit to screen** button (or double-click the title bar) to fill the screen. Size and position are saved.
- **Library**: your Liked Songs, playlists and saved albums. Open one to see its songs and play from any of them, or press ▶ to play it all. Spotify only lets this kind of app list the songs of playlists you own or collaborate on; other playlists can still be played as a whole.
- **Search**: songs, playlists or albums. Click a song to play it or press **+Q** to queue it; click a playlist or album to open it.
- **Hotkeys** for play/pause, next, previous, volume up/down, opening the player and toggling the HUD. They're unbound by default; set them in Options → Controls → Tunewire.

The music plays in your Spotify app (desktop or phone); Tunewire is the remote control. Spotify doesn't let other apps take its audio stream, so the sound can't come from Minecraft itself. Playback control needs **Spotify Premium**.

## Setup (once)

1. Open <https://developer.spotify.com/dashboard> and create an app. Any name and description will do.
2. Add the Redirect URI **`http://127.0.0.1:43827/callback`** exactly, and tick **Web API**.
3. Copy the app's **Client ID**.
4. In game, run `/tunewire` and open **Settings**. Paste the Client ID, then **Save**, then **Connect Spotify**.
5. Approve access in the browser tab that opens. You can then close the tab.

Since February 2026, Spotify's Development Mode needs the app owner to have Premium, and each app allows up to five users. If friends want to use your app, add them under *User Management* in the dashboard, or have them create their own app.

## Commands

| Command | What it does |
|---|---|
| `/tunewire` | Open the player |
| `/tunewire login` / `logout` | Connect or disconnect Spotify |
| `/tunewire clientid <id>` | Set the Client ID |
| `/tunewire hud` | Drag and resize the HUD elements |
| `/tunewire perf` | Show how much frame time Tunewire uses (top-right overlay) |

## Files

- `config/tunewire.json`: settings.
- `config/tunewire-auth.json`: your Spotify tokens. **Don't share this file.** Disconnecting deletes it.

## Build

Loom 1.18 needs Gradle running on JDK 25. The mod itself targets Java 21.

```
./gradlew build -Dorg.gradle.java.home="C:/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot"
```

The jar is written to `build/libs/tunewire-<version>+1.21.11.jar`. It requires Fabric API.

Not affiliated with Spotify. Spotify is a trademark of Spotify AB.
