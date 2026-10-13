# OldGround for Android

Native Kotlin + Jetpack Compose app for metal detecting with old maps. It is a separate codebase from the iOS app.

- Import old maps (Photos, Files or Camera) and anchor them to today's map. Two anchors give a straight fit; three or more give a rubber-sheet mesh.
- Hunt map: Overlay, Swipe, Split and Drive. Layers are the old map, LiDAR (USGS 3DEP, Hillshade Stretched by default), satellite, USGS Topo and streets.
- Pins, plus tracks that keep recording with the screen off. GPX export, and offline areas.
- Uses free map sources only: OpenFreeMap, USGS and Photon.

## Build

Every push builds on GitHub Actions. The latest APK is always at:
https://github.com/anattalife/detecting-map-android/releases/download/test-build/oldground.apk

The test build is signed with `app/oldground-test.keystore`, so new builds install over old ones. Use a private release key before publishing to Google Play.
