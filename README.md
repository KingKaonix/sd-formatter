# SDFormatter

Android app to format SD cards connected via USB OTG to FAT or FAT32.

## How it works

Detects USB mass storage devices via the USB Host API and lists them. When you
select a device and filesystem type, the app attempts to format it using one of
two methods:

1. **Root path** — runs `mkfs.vfat -F 16/32` via `su` for full control over the
   filesystem type.
2. **Non-root path** — uses `StorageVolume.format()` (API 24+) which formats the
   volume to the system default filesystem.

Root is strongly recommended for reliable FAT/FAT32 formatting.

## Building

```bash
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Requirements

- Android 7.0+ (API 24)
- Device with USB OTG support
- For FAT/FAT32 selection: **root access**

## Permissions

- `USB_PERMISSION` — detect USB mass storage devices
- `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` — legacy, for file access on older Android

## CI / Releases

Every push to `main` builds via GitHub Actions. Pushing a tag `v*` creates a
GitHub Release with the debug APK attached.

```bash
git tag v1.0.0
git push origin v1.0.0
```

## License

MIT
