# TerraTrack Android

TerraTrack is an Android off-road navigation application for adventure motorcycles, SSVs and UTVs. It is being built for long-distance GPX travel, including routes such as the Trans Euro Trail (TET).

## Version 0.3.0 features

- Native Android application in Kotlin and Jetpack Compose, with an English interface.
- Mapsforge offline vector map engine.
- Import of regional Mapsforge `.map` files, including compatible OpenAndroMaps files.
- Direct HTTPS download of a `.map` file or a ZIP archive containing one. Downloads are stored locally, with removable app-specific storage preferred when Android exposes it.
- GPX import and local persistence.
- GPX parser for track points, route points and waypoints.
- GPX track geometry drawn over the offline map as a high-visibility red line.
- GPX waypoint markers shown on the map and listed in the track library.
- Track distance and distance to the nearest point on the original GPX geometry.
- GPX direction reversal without changing the imported source file.
- Track-following mode with no automatic route recalculation.
- Point-to-point mode: tap the map to set a target and view straight-line distance and bearing. This is compass guidance, not a calculated route.
- GPS speed, altitude when available, and device compass heading.
- Rally layout with large controls and map zoom / GPS recenter buttons.
- Controls hide after inactivity and reappear after a map tap.
- Touchscreen rain lock with a three-second hold to unlock. Android system navigation remains available.
- Bluetooth media-key mapping: Volume Up / Down adjusts map zoom, Media Next recentres GPS, and Media Previous switches Map / Recorder.
- GPX recorder with start, pause, resume and stop.
- Recording uses an Android location foreground service and a persistent notification, and can continue with the screen off while the service is active.
- Recorded GPX includes timestamped GPS points and altitude when the device supplies it. Paused sections are stored as separate GPX track segments.
- Saved recording list and GPX export through Android's document picker.
- Local processing of location and tracks. No account or location upload is implemented.

## Known limitations and validation still required

- Maps are imported or downloaded from a direct provider URL. There is no built-in regional catalogue or automatic discovery of OpenAndroMaps downloads.
- Map display uses the built-in Mapsforge Motorider theme. Importing separate external render themes is not implemented.
- GPX waypoints work as local POIs, but dedicated fuel, water, workshop and campsite datasets, categories and filters are not included yet.
- Point-to-point mode displays straight-line distance and bearing. It does not calculate a traversable path.
- No direction arrows along the GPX line, turn-point warnings or configurable off-track alerts are included yet.
- There is no enforced 30 FPS renderer limit, adaptive day/night mode or hillshade layer yet.
- Bluetooth commands are mapped through standard Android key events. Carpe Iter, WunderLINQ and other hardware-specific commands still need tests with the actual controllers.
- Background recording depends on Android permissions, location settings and battery-management policy. Start recording while the app is visible and after granting location permission.
- Community accounts, route publishing, group functions and P2P sharing are not included in this version.

## Build the APK

A GitHub Actions workflow builds the debug APK on pushes to the `terratrack-rebuild` branch.

1. Open the repository's **Actions** tab.
2. Select **Build TerraTrack APK**.
3. Open the latest completed run.
4. Download the `TerraTrack-0.3.0-debug` artifact.
5. Extract `app-debug.apk` and install it on an Android device.

A successful code upload is not proof of a successful APK build. Install the APK only after GitHub Actions reports a successful build.
