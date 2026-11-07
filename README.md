# TerraTrack Android

**TerraTrack** is an Android off-road navigation project for adventure motorcycles, SSVs and UTVs. It is designed around long-distance GPX track following, including routes such as the Trans Euro Trail (TET).

## Product principles

- Offline-first navigation.
- Imported GPX tracks remain the source of truth.
- No automatic route recalculation while following a GPX track.
- Clear, high-contrast controls designed for use with riding gloves.
- Location is used on the device and is not uploaded by this prototype.

## Current milestone: 0.1.0 foundation

Implemented:
- New TerraTrack identity and English interface.
- Native Android app using Kotlin and Jetpack Compose.
- Local GPX file import through Android's document picker.
- Asynchronous GPX parsing, including track and route points.
- Track distance calculation.
- Reverse track direction without changing the imported source.
- Basic foreground GPS updates while the app is open.
- Straight-line distance from the current GPS fix to the nearest GPX point.

Not yet implemented:
- Offline map renderer and regional map downloads.
- Drawing GPX geometry on the map.
- Background GPS foreground service.
- Rally Mode and rain/touch lock.
- Bluetooth media-key controls.
- Offline points of interest and community features.

## Build

The GitHub Actions workflow builds a debug APK on pushes to the `terratrack-rebuild` branch or when manually started.

1. Open the repository's **Actions** tab.
2. Select **Build TerraTrack APK**.
3. Open the latest run after it completes.
4. Download the `TerraTrack-0.1.0-debug` artifact.
5. Extract `app-debug.apk` and install it on an Android device.

The APK is only considered ready after the GitHub Actions build succeeds.
