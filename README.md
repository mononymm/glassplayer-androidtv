# Glass for Android TV

> **Personal project. Not made for public use.** It's built for one specific setup (a Plex Media Server
> and a TCL Google TV) and may not work on yours. There's no support and no warranty, and it changes often.
> Use it at your own risk.

A Plex player for Android / Google TV that uses **two players** and picks one for each file:

| File | Player | Why |
|---|---|---|
| Dolby Vision or HDR10/HLG | **ExoPlayer** (Media3) | Uses the TV's own decoders, so Dolby Vision, HDR and audio passthrough work |
| Styled subtitles (ASS/SSA, typical for anime) | **mpv** (libmpv + libass) | Full subtitle styling, drawn *into* the picture so they can't drift out of sync |
| Everything else | ExoPlayer (changeable in Settings) | |

You can override the choice per library (Library page or Settings), per show or movie (its page or a
long press), or switch during playback (the player button in the controls).

## Features

- Plex sign-in with a code at plex.tv/link; picks the fastest connection (home network first)
- Home: Continue watching, Recently added per library (whole shows, not single episodes)
- Libraries with sorting, show pages with seasons and episodes, search
- Progress is saved to Plex while you watch; finished episodes are marked watched; Up next with a countdown
- Remote: **OK** play/pause, **←** back 10 s (hold to rewind), **→** forward 30 s (hold for 2× speed),
  **↑/↓** controls. Hold **OK** on any title (or press Menu) for more options
- Matches the TV's refresh rate to the video (24p etc), chapter skip, audio/subtitle/speed menus
- Subtitle delay setting for ExoPlayer (the TV shows menus a little before the processed video)

## Install

1. On the TV, install **Downloader** (by AFTVnews) from the Play Store.
2. Settings → System → About → click **Android TV OS build** 7 times to turn on Developer options. Then
   allow **Downloader** under *Apps → Security & restrictions → Unknown sources* (the exact menu
   names vary a little between TVs).
3. In Downloader, enter:
   `https://github.com/mononymm/glassplayer-androidtv/releases/latest/download/Glass-TV.apk`
4. Install, open **Glass**, and sign in.

Updates: do step 3 again. New versions install over the old one and keep your sign-in.

## Privacy

Your Plex token stays on the TV (app-private storage) and is sent only to plex.tv and your own Plex
server, in request headers (never in links). Nothing else is collected or sent anywhere.

## Verify a download, or build it yourself

Every release is built by GitHub Actions straight from this repository. The release notes list the
APK's SHA-256 and a link to the build log, and each APK has a signed build attestation:

```
gh attestation verify Glass-TV.apk --repo mononymm/glassplayer-androidtv
```

To build it yourself: install JDK 21 and the Android SDK, then run `./gradlew assembleRelease`
(it's signed with a debug key unless you provide `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`
and `KEY_PASSWORD`). Note that an APK signed with a different key can't update one installed from the
releases; uninstall first.

## Licence

GPL-3.0. The app bundles [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android)
(mpv, FFmpeg, libass and others) and the [Jellyfin FFmpeg decoder for Media3](https://github.com/jellyfin/jellyfin-androidx-media),
which are GPL licensed.
