# Tunewire

A client-side Fabric mod for Minecraft 1.21.11 that puts your Spotify inside the game:

- **Now-playing HUD**: cover art, title and artist (they scroll if too long), and a progress bar.
- **Synced lyrics**: shown on the HUD and in the player, timed to the song. They come from [LRCLIB](https://lrclib.net), because Spotify has no public lyrics API.
  - Click a line to jump to it.
  - If the timing is off, switch to another lyric version or nudge the offset.
- **Player screen**:
  - seek bar, play/pause, next/previous, shuffle, repeat and volume;
  - a device picker;
  - scrolling lyrics.
- **Search and queue**: search Spotify, click a result to play it, or press **+Q** to queue it.
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
