# Auxio Playlists — fork notes

A fork of [Auxio](https://github.com/OxygenCobalt/Auxio) (GPLv3, `v4.1.5`) that makes Auxio pick up
playlists that already exist on the device instead of requiring one-by-one manual imports.

Everything below is the entire delta against upstream. Upstream files are untouched apart from the
marked patches, so `git rebase` onto a future release stays trivial.

## The problem

Upstream Auxio 4.1.5 has no playlist scanning at all:

* `musikr`'s only MediaStore query targets `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI`
  (`audio_media` view), which contains `media_type=2` rows only. Playlist files are indexed by
  MediaProvider as `media_type=4` (`audio_playlists`), so they are never enumerated.
* `ExploreStep` classifies `audio/x-mpegurl` as `NotAudio` and drops it.
* The Playlists tab is therefore backed purely by Auxio's own Room DB, filled only by
  "New playlist" or by the SAF "Import playlist" action.

Gramophone solves this by reading the system playlist table
(`MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI`, i.e. `content://media/external/audio/playlists`,
filtering `media_type=4` on R+) and then parsing each playlist file (M3U/PLS/WPL/XSPF) from its
`_data` path, falling back to the `audio_playlists/<id>` members projection on older releases.
This fork does the same, but reuses Auxio's existing M3U reader and its existing
`MutableLibrary.createPlaylist` persistence path.

## Changes

1. **`musikr/src/main/java/org/oxycblt/musikr/playlist/ExternalPlaylists.kt`** (new, ~150 lines)
   Enumerates `MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI`, resolves each row's path with
   musikr's own `MediaStorePathInterpreter` (so it works on all API levels / OEM variants), opens
   the playlist through the content resolver, and parses it with the same `M3U` implementation the
   import feature uses — relative entries are resolved against the playlist's real directory.
   Playlists already present in the DB (matched by name) are skipped, which makes it idempotent.

2. **`musikr/.../pipeline/EvaluateStep.kt`** (patched)
   After the library graph is built, adopt the playlists reported by `ExternalPlaylists` via
   `MutableLibrary.createPlaylist(name, songs)`, which also persists them through
   `StoredPlaylists` (Room). Wrapped in try/catch so a broken playlist file can never fail a scan.

3. **`musikr/build.gradle` / `media/libraries/decoder_ffmpeg/build.gradle`** (patched)
   Native `taglib`/`ffmpeg` are shipped prebuilt in `app/src/main/jniLibs/arm64-v8a` (extracted from
   the upstream 4.1.5 release APK, same version's JNI) instead of being compiled from source, so the
   NDK/CMake/submodules are not needed to build. The `media` submodule change is kept as a patch in
   `patches/0001-media-skip-ffmpeg-native-build.patch`.

4. **Rebrand for side-by-side installs**
   `applicationId` is `dev.alandao.auxio` (`+ ".debug"` for debug builds), the cover provider
   authority is `dev.alandao.auxio.image.CoverProvider`, and the label is "Alan Music Player".
   The upstream `org.oxycblt.auxio` install keeps working, so you can compare both.

## Behaviour notes

* Scanning happens as part of the normal library index (app start / media changes). After the first
  scan, the playlists live in Auxio's DB, so they can be renamed, edited, and are not re-created
  while a playlist with that name exists.
* Deleting a playlist in Auxio will bring it back on the next scan if its `.m3u` is still on disk.
* Edits made inside Auxio do not write back to the `.m3u` file (same as upstream's import, which is
  a one-time copy). Use "Export playlist" if you need the file updated.

## Build

```sh
# JDK 21 + Android SDK 37 (no NDK needed)
brew install openjdk@21
brew install --cask android-commandlinetools
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"

git submodule update --init media
git -C media apply ../patches/0001-media-skip-ffmpeg-native-build.patch

./scripts/build.sh debug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are `debuggable`, so app-private data (`databases/playlist.db`) is inspectable with
`adb shell run-as dev.alandao.auxio.debug`.
