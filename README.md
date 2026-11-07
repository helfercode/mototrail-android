# TerraTrack Android

TerraTrack is an Android off-road navigation app for adventure motorcycles, SSVs and UTVs. It is designed for long-distance GPX travel, including routes such as the Trans Euro Trail (TET).

## Implemented in version 0.2.0

- Native Android application in Kotlin and Jetpack Compose, with an English interface.
- Mapsforge offline vector map engine using local `.map` files.
- Import compatible OpenAndroMaps / Mapsforge regional map files into local app storage. The storage selector uses the removable app-specific storage location when Android exposes one.
- GPX import, local persistence, parsing of track points, route points and waypoints.
- GPX track drawn as a bright line over the offline map.
- Offline GPX waypoints drawn as map markers and listed in the track library.
- Track distance and closest straight-line distance to the original GPX geometry.
- Track direction reversal without modifying the imported source file.
- Track-following mode does not invoke automatic route recalculation.
- Rally layout with large controls, quick map zoom and GPS recenter.
- Touchscreen rain lock with a three-second hold to unlock; Android system navigation remains available.
- Bluetooth media-key mapping: Volume Up / Down adjusts map zoom, Media Next recentres on GPS and Media Previous switches between Map and Recorder.
- GPX recorder with start, pause, resume and stop.
- Recording runs in a location foreground service and keeps a persistent notification while active.
- Recorded GPX includes timestamped GPS points and altitude when the device supplies it.
- Local recording list and GPX export through Android's document picker.
- GPS readings are processed locally; the app does not upload tracks or location.

## Important limitations of this build

- Offline maps are imported as Mapsforge `.map` files. There is no in-app regional map catalogue or automatic download service yet.
- Map appearance uses the built-in Mapsforge Motorider render theme. Separate external render-theme installation is not yet included.
- Offline POIs are currently GPX waypoints. Dedicated fuel, water, workshop and campsite datasets and filtering are not yet included.
- Track following shows the distance to the nearest point on the original GPX line. Turn-by-turn instructions, direction arrows and off-track alerts are not yet included.
- Bluetooth mapping uses standard Android key events. Carpe Iter, WunderLINQ and other hardware-specific commands require testing with the actual remote.
- Background GPS recording depends on Android permissions, location settings and battery-management policy. Start recording while the app is visible and location permission has been granted.
- Automatic night/day sensor switching, hillshade overlays and an enforced 30 FPS renderer cap are not yet included.
- Community sharing and route publishing are not yet included.

## Build APK

A GitHub Actions workflow builds the debug APK on pushes to the `terratrack-rebuild` branch.

1. Open the repository's **Actions** tab.
2. Select **Build TerraTrack APK**.
3. Open the latest completed run.
4. Download the `TerraTrack-0.2.0-debug` artifact.
5. Extract `app-debug.apk` and install it on an Android device.

A successful commit or code upload is not proof of a successful APK build. The APK is only ready for installation after the GitHub Actions run finishes successfully.
