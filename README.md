# SquishISO

**Shrink your game discs.** SquishISO is an Android app that compresses PSP `.iso` disc images into `.cso` files that stay fully playable, and turns CSOs back into ISOs. Everything runs on your phone, offline.

## Features

- **Small CSOs that still work everywhere.** In **Max** mode each block is compressed with both Zopfli and zlib level 9, and the smaller result is kept. Output is standard deflate, so any CSO reader can play it.
- **Two block sizes:**
  - **Compatible (2 KB, default):** plays on real hardware running custom firmware, and in PPSSPP.
  - **Smallest (16 KB):** usually 10–20% smaller still, PPSSPP only.
- **Fast:** blocks are compressed on every CPU core while the next chunk is read.
- **Verified:** every new file is decoded and checked against a CRC32 of the source. **Delete originals** only runs after that check passes.
- **Two-way:** ISO → CSO, CSO → ISO, and re-compressing an existing CSO.
- **Game info:** shows each game's icon, title and disc ID. An option names files `Title [ULUS10036].cso`.
- **Batch queue:** add files, a whole folder (searched 4 levels deep), or use "Open with" / "Share" from a file manager. Shows speed and time left. Existing files can be skipped, replaced or kept.
- **Runs in the background** with a progress notification, a Cancel button and a summary when it finishes.
- **Private:** no internet permission, no ads, no tracking, no storage permission. Files are opened with the system file picker.
- **Runs on Android 7.0 and newer** (API 24–35), on any CPU. The app is pure Java with no native code.

## Install

1. Download `SquishISO.apk`.
2. Open it and allow "Install unknown apps" for your browser or file manager when asked.
3. If Google Play Protect warns about an unknown app, tap **More details → Install anyway**. That warning appears for any app installed from outside the Play Store.

If Android says **App not installed**, a copy signed with a different key is already installed. Uninstall it, then install again.

## Use

1. **Add files** or **Add folder**.
2. **Choose folder…** to pick where to save the output. Android won't let apps write to the root of `Download`, so pick a subfolder such as `PSP/ISO`.
3. Pick the compression settings and tap **Squish to CSO**.

## Builds and signing

Every push runs the **SquishISO APK** workflow. It runs the unit tests, builds a release APK and publishes it to the `squishiso-latest` pre-release.

Release APKs must always be signed with the same key, or updates won't install over earlier versions. CI reads the key from three repository secrets:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_B64` | the release `.jks` file, base64-encoded |
| `SIGNING_KEYSTORE_PASSWORD` | its password |
| `SIGNING_KEY_ALIAS` | `squishiso` |

Without these secrets, CI falls back to a debug key. Don't distribute those builds.

```sh
./gradlew test assembleRelease     # needs the Android SDK (platform 35)
```

## CSO format notes

CISO v1: a 24-byte header, then `blocks + 1` little-endian u32 index entries, then the block data. Each block is a raw deflate stream. If compressing a block doesn't make it smaller, the block is stored uncompressed and bit 31 of its index entry is set. Images that could exceed 2 GiB use the header's alignment field.

## Legal

Only convert games you own. PSP is a trademark of Sony Interactive Entertainment. SquishISO is not affiliated with or endorsed by Sony.

`app/src/main/java/ru/eustas/zopfli` is the Java port of Google's Zopfli (Apache License 2.0), included unmodified. See `third_party/zopfli`.
