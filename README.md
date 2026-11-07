# TerraTrack Android

TerraTrack is a native Android off-road navigation application for adventure motorcycles, SSVs and UTVs. It is designed for long GPX journeys, including routes such as the Trans Euro Trail (TET).

## Version 0.4.0 feature set

- Kotlin and Jetpack Compose, with an English interface and a high-contrast Rally layout.
- Mapsforge offline vector map rendering from local `.map` files, including compatible OpenAndroMaps regional maps.
- Import a map file from device storage, or download a direct HTTPS `.map` file / ZIP package containing one.
- Offline GPX import, parsing and local track library.
- GPX track line, direction chevrons, and GPX waypoints drawn over the offline map.
- Track distance and nearest distance to the original GPX line, calculated against the line segments rather than just individual points.
- Reverse track direction for on-screen guidance and background turn guidance without modifying the original GPX file.
- Track-following mode with no automatic route recalculation.
- Background GPX navigation through a location foreground service, including a persistent notification.
- Off-track warning when the GPS position is more than 50 metres from the GPX line; the warning clears below 40 metres to reduce GPS jitter.
- Ahead-of-turn notifications (left / right) derived from changes in the geometry of the GPX line. These are geometric guidance cues, not road-network turn-by-turn routing.
- Point-to-point mode: tap the map or select a saved POI to show straight-line bearing and distance. It does not calculate a traversable route.
- GPS speed, altitude when available and device compass heading.
- Rally controls sized for glove use, automatic control hiding, GPS recenter and map zoom.
- Rain / touchscreen lock with a three-second hold to unlock. Android system navigation remains available.
- Day / Night / Auto theme, using the ambient light sensor when available and local time as a fallback.
- Standard Android media-key mapping: Volume Up / Down adjusts map zoom, Media Next recentres GPS, Media Previous switches between Map and Recorder.
- GPX recording with Start, Pause, Resume and Stop, including a running duration, distance and point count.
- Recording runs in a location foreground service with a persistent notification while the screen is off, subject to Android permissions and device battery policy.
- Recorded GPX includes timestamped GPS points and altitude when available. Paused intervals become separate GPX track segments.
- Import, reverse, export and share GPX files through Android's document picker and Sharesheet.
- Offline POI download via OpenStreetMap Overpass for fuel, drinking water / springs, motorcycle and vehicle workshops, campsites and caravan sites. Choose a 10, 25 or 50 km search radius around the current GPS fix.
- POI results are saved locally, overlaid on the map, can be hidden with a map control, and can be selected as point-to-point destinations.
- The optional Overpass lookup sends the current centre coordinates and selected radius to the public Overpass service. GPX navigation and local track recordings are not uploaded.

## Current limits and real-device testing required

- Maps must be imported or downloaded using a direct provider URL. There is no built-in regional map catalogue or automatic OpenAndroMaps region discovery.
- Map style uses the built-in Mapsforge Motorider render theme. Importing a separate render theme or custom contour / DEM layers is not implemented.
- Hillshade and a hard 30 FPS cap are not implemented.
- The POI download relies on the public Overpass service and needs connectivity at download time. Saved POIs are available offline afterwards.
- Point-to-point navigation is a straight-line compass aid, not road or trail routing.
- Turn notifications are inferred from GPX geometry. Dense or noisy tracks can produce imperfect turn cues; the GPX itself remains authoritative.
- Android may restrict background location or notifications according to the user's permissions, battery settings and device manufacturer. Start recording or GPX navigation from the visible app after granting location access.
- Bluetooth mapping uses standard Android key events. Carpe Iter, WunderLINQ and other hardware remotes need verification on the actual controller and phone.
- There is no community account system, hosted route publishing, group tracking or P2P communication backend in this build.
- The source has not yet been confirmed by a successful Android APK build or by testing on physical hardware. GPS accuracy, SD-card availability, map display, notifications and Bluetooth controls all require device validation.

## Build the APK

A GitHub Actions workflow builds the debug APK when code is pushed to the `terratrack-rebuild` branch.

1. Open the repository's **Actions** tab.
2. Select **Build TerraTrack APK**.
3. Open the latest run after it finishes.
4. Download the `TerraTrack-0.4.0-debug` artifact.
5. Extract `app-debug.apk` and install it on an Android device.

A successful code upload is not proof of a successful APK build. The APK should only be installed after GitHub Actions reports a successful build.
