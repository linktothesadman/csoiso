# ISO2CSO for Android

A fast Android app that compresses PSP `.iso` images to `.cso`, and turns CSOs back into ISOs.

## Features

- **Small, compatible CSOs.** In **Max** mode every block is compressed with Zopfli and with zlib level 9, and the smaller result is kept. That's 2–3% smaller than zlib alone. The output is standard deflate, so any CSO reader can decode it.
- **Two block-size modes:**
  - **Compatible (2 KB blocks, default):** plays on a real PSP running custom firmware, and in PPSSPP.
  - **Smallest (16 KB blocks):** usually 10–20% smaller still, but PPSSPP only.
- **Uses every CPU core.** Blocks are compressed in parallel while the next chunk is being read.
- **Verified output.** Each new file is decoded and checked against a CRC32 of the source. **Delete originals** only runs after that check passes.
- **Two-way conversion:** ISO → CSO, CSO → ISO, and re-compressing an existing CSO (for example, from 16 KB to 2 KB blocks).
- **Game info:** the queue shows each game's icon, title and disc ID, read from the image. An option names output files `Title [ULUS10036].cso`.
- **Easy to add files:** pick files, add a whole folder (searched up to 4 levels deep), or use "Open with" / "Share" from a file manager.
- **Queue:** per-file status, overall progress, speed and time remaining. Files that already exist can be skipped, replaced or kept as a second copy.
- **Runs in the background:** a foreground service shows a progress notification with a Cancel button, and posts a summary when it finishes.
- A running total of the space you've saved.
- **No storage permission:** files are opened with the system file picker. Targets Android 15 (SDK 35) and runs on Android 8.0+, including the Galaxy S23 Ultra.

## Install

Every push runs the **ISO2CSO APK** GitHub Actions workflow. It runs the unit tests, builds a signed release APK, and publishes it to the `iso2cso-latest` pre-release.

1. On your phone, open the repo's **Releases** page → **ISO2CSO (latest build)** → download `iso2cso.apk`.
2. Open the downloaded file and allow "Install unknown apps" for your browser or file manager when asked.

If Android says **App not installed** or **package conflicts**, a copy signed with a different key is already installed. Uninstall it once, then install the new APK.

To keep every future build signed with one key, add three repository secrets: `SIGNING_KEYSTORE_B64` (the `.jks` file in base64), `SIGNING_KEYSTORE_PASSWORD` and `SIGNING_KEY_ALIAS`. Without them, CI signs with a cached debug key.

## Use

1. **Add files** or **Add folder**.
2. **Choose folder…** to pick where to save the output. Android won't let apps write to the root of `Download`, so choose a subfolder such as `PSP/ISO`.
3. Pick the compression settings and tap **Convert to CSO**.

## CSO format notes

CISO v1: a 24-byte header, then `blocks + 1` little-endian u32 index entries, then the block data. Each block is a raw deflate stream. If compressing a block doesn't make it smaller, the block is stored uncompressed and bit 31 of its index entry is set. Images that could exceed 2 GiB use the header's alignment field.

## Build locally

```sh
./gradlew test assembleRelease     # needs the Android SDK (platform 35)
# APK: app/build/outputs/apk/release/app-release.apk
```

## Third-party code

`app/src/main/java/ru/eustas/zopfli` is the Java port of Google's Zopfli by Eugene Klyuchnikov, included unmodified under the Apache License 2.0. See `third_party/zopfli`.
