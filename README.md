# ISO2CSO for Android

A small, fast Android app that compresses PSP `.iso` images to `.cso`.

- **Quick:** every block is compressed in parallel across all CPU cores, and the next chunk is read while the current one compresses.
- **Small output:** every block is compressed at deflate level 9. "Extra squeeze" also tries a second deflate strategy on each block and keeps the smaller result.
- **Two block-size modes:**
  - *Smallest files (16 KB blocks):* usually about 5% smaller than 2 KB blocks. Plays in PPSSPP.
  - *Compatible (2 KB blocks):* the standard CSO layout, which a real PSP running custom firmware can also read.
- Converts several ISOs in one run, in the background (foreground service with a progress notification and a Cancel button).
- Can delete the original ISO after it converts successfully.
- Needs no storage permission. Files are chosen with the system file picker (Storage Access Framework).
- Targets Android 15 (SDK 35) and runs on Android 8.0 and later, including the Galaxy S23 Ultra.

## Install

Every push that changes `iso2cso-android/` runs the **ISO2CSO APK** GitHub Actions workflow. The workflow runs the unit tests, builds a signed release APK, and publishes it to the `iso2cso-latest` pre-release.

1. On your phone, open the repo's **Releases** page → **ISO2CSO (latest build)** → download `iso2cso.apk`.
2. Open the downloaded file and allow "Install unknown apps" for your browser or file manager when asked.

## Use

1. **Choose ISOs…**: pick one or more `.iso` files.
2. **Choose folder…**: pick where to save the `.cso` files. The app remembers this folder. Android won't let apps write to the root of `Download`, so choose or create a subfolder such as `Download/PSP` or `PSP/GAME`.
3. Choose a compression mode and tap **Convert**.

## Build locally

```sh
cd iso2cso-android
./gradlew test assembleRelease     # needs the Android SDK (platform 35)
# APK: app/build/outputs/apk/release/app-release.apk
```

## CSO format notes

CISO v1: a 24-byte header, then `blocks + 1` little-endian u32 index entries, then the block data. Each block is a raw deflate stream. If compressing a block doesn't make it smaller, the block is stored uncompressed and bit 31 of its index entry is set. Images that could exceed 2 GiB use the header's alignment field.
