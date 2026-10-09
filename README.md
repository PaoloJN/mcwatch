# mcwatch

Watch YouTube (or any video link) on your Minecraft HUD while you play.

Paste a link in chat and the video plays in a corner of the screen. It's drawn like the hotbar, so you keep full control of the game: walk, mine and build while it plays. Pause, skip, resize and move it with the keyboard without opening any menu.

- **Minecraft:** 26.1.2 (Fabric)
- **Platform:** macOS with Homebrew. Linux should work with small path changes.

## Requirements

- [Fabric Loader](https://fabricmc.net/use/) 0.19.5+ and [Fabric API](https://modrinth.com/mod/fabric-api)
- `mpv`, `yt-dlp` and `deno`, from Homebrew:

  ```sh
  brew install mpv yt-dlp deno
  ```

  mpv decodes the video and plays the audio. yt-dlp turns YouTube links into playable streams, and it needs deno to solve YouTube's JavaScript challenges.

## Install

1. Download `mcwatch-<version>.jar` from [Releases](../../releases), or [build it](#building).
2. Put it in `~/Library/Application Support/minecraft/mods/` next to Fabric API.
3. Launch the game with your Fabric profile.

## Usage

| Command | What it does |
|---|---|
| `/watch` | Play the link on your clipboard |
| `/watch <link>` | Play a link |
| `/watch pause` | Pause / resume |
| `/watch stop` | Stop and close the video |
| `/watch hide` | Hide / show the video |
| `/watch seek <seconds>` | Skip forward or back, e.g. `-30` |
| `/watch volume <0-130>` | Set the volume |
| `/watch size small\|medium\|large\|huge` | Set the size |
| `/watch corner top-right\|top-left\|bottom-right\|bottom-left` | Snap to a corner |

The video stops automatically when you leave the world or server.

### Keys (while playing)

| Key | Action |
|---|---|
| ↑ | Pause / resume |
| ← / → | Back / forward 5 s (hold Shift for 30 s) |
| ↓ | Hide / show |
| `-` / `=` | Smaller / bigger |
| `,` / `.` | Volume down / up |
| `;` | **Move mode**: the arrow keys slide the window (Shift = faster), and `;` again saves the position |

Rebind any of these under *Options → Controls → Key Binds → mcwatch (video)*. Size, position and volume are saved to `config/mcwatch.properties`.

## How it works

- **Playback:** [libmpv](https://mpv.io/manual/master/#embedding-into-other-programs-libmpv) is loaded through JNA, which Minecraft already ships. mpv handles yt-dlp, decoding, audio and A/V sync.
- **Drawing frames:** a background thread draws each new frame into memory with mpv's software render API. The game thread copies the latest frame into a `DynamicTexture` and draws it as a HUD element.
- **Fast skipping:** streaming straight from YouTube is throttled to about real-time speed, which makes skips slow. So while the video starts streaming, yt-dlp downloads the full file in the background (720p max) to a temp folder. When it finishes, playback switches to the local copy at the same position, and skipping becomes instant. The temp file is deleted when you stop, change videos or quit.
- **Rapid skips:** quick repeated skips are combined into a single jump, and a watchdog restarts playback if the stream ever stalls.

## Building

Requires JDK 25.

```sh
./gradlew build
# → build/libs/mcwatch-<version>.jar
```

For debugging, set `MCWATCH_MPV_LOG=/path/to/mpv.log` before launching to get mpv's verbose log.

## License

MIT
