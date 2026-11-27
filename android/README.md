# Clipp for Android

Android port of Clipp. It runs the same C++ sync core as the desktop and iOS
apps (`src/`, compiled into `libclipp.so` through JNI), so it pairs and syncs
with them using the normal group name + passphrase.

- **Receiving** is automatic: a background service (with a small persistent
  notification) keeps peer connections open and puts incoming items on the
  phone's clipboard.
- **Sending**: Android only lets the focused app read the clipboard, so send
  with the **Send clipboard** button, the Quick Settings tile, the
  notification action, or the share sheet.
- **History**: tap an item to copy it; swipe left (or long-press) to delete it
  on all devices, with Undo.

## Build

CI (`.github/workflows/android-ci.yml`) builds a signed APK on every push to
`android`. Download it from the run's artifacts and install it on the phone.

Locally: build the native deps with vcpkg for `arm64-android-release` from
`android/native-deps`, then
`gradle -p android assembleRelease -PclippDepsDir=<vcpkg_installed>/arm64-android-release`.
