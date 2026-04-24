# AR Tracer

A lightweight Android app for tracing drawings on paper. The phone's rear camera shows the
paper in real time, and a reference image is composited on top with adjustable opacity, position,
scale, rotation, and a horizontal flip — so you can copy any reference by hand.

- **100 % offline**, no ads, no subscriptions, no analytics, no network permission.
- **Kotlin only**, native Android Views, CameraX for the preview.
- Works on **Android 8.0 (API 26) and up**.
- Built for a small APK and smooth performance on entry-level devices (resource shrinking,
  ABI/density splits via App Bundle, image downsampling, no third-party UI frameworks).

---

## Features

| Group           | Feature                                                                     |
| --------------- | --------------------------------------------------------------------------- |
| Camera          | CameraX live preview from the rear camera                                   |
| Image overlay   | Pick a PNG/JPG from the gallery (system Photo Picker on Android 13+)        |
| Transform       | One-finger drag, pinch to zoom, two-finger rotate, double-tap to reset      |
| Opacity         | 0–100 % slider, applied in real time                                        |
| Lock            | Freezes the overlay so you can rest your hand on the screen while drawing  |
| Grid            | Toggleable rule-of-thirds + 8×8 alignment grid                              |
| Flip            | Mirror the reference horizontally                                           |
| Reset           | Restore default position, scale, rotation, opacity                          |
| Fullscreen      | Hide system bars and chrome for a clean tracing surface                     |
| Screenshot      | Saves a composite (camera + overlay) PNG to `Pictures/AR Tracer`            |
| Persistence     | The last-loaded image is restored automatically on next launch              |

No internet permission is declared in the manifest, so the app cannot make network requests.

---

## Project layout

```
android-ar-tracer/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/artracer/app/
        │   ├── MainActivity.kt           # camera, controls, screenshot, persistence
        │   ├── OverlayView.kt            # reference image + drag/scale/rotate/flip
        │   ├── GridOverlayView.kt        # rule-of-thirds + 8x8 grid
        │   └── RotationGestureDetector.kt# two-finger rotation gesture
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/                 # vector icons + chip/panel backgrounds
            ├── mipmap-anydpi-v26/        # adaptive launcher icon
            ├── values/                   # colors, strings, themes
            └── xml/                      # backup + data extraction rules
```

---

## Build instructions

### Option A — Android Studio (recommended)

1. Install **Android Studio Hedgehog (2023.1) or newer** with the Android SDK Platform 34
   and Build-Tools 34 installed via the SDK Manager. JDK 17 is bundled with Android Studio.
2. Open Android Studio → **File ▸ Open…** → select the `android-ar-tracer/` folder.
3. Wait for Gradle sync to finish. Android Studio will download the Gradle 8.7 wrapper and
   the Android Gradle Plugin 8.5.2 the first time.
4. Plug in an Android device (developer options + USB debugging enabled) or start an emulator.
   The emulator's virtual camera works for the preview but is mostly useful for verifying the UI.
5. Press **Run ▸ Run 'app'** (or `Shift + F10`).

To produce a release APK:

1. **Build ▸ Generate Signed App Bundle / APK… ▸ APK**.
2. Pick or create a keystore, choose `release`, and finish the wizard.
3. The APK is written to `app/build/outputs/apk/release/app-release.apk`.

### Option B — Command line

Requires JDK 17 and the Android SDK installed locally with the `ANDROID_HOME` (or
`ANDROID_SDK_ROOT`) environment variable pointing at it. The project uses the Gradle wrapper,
so no separate Gradle install is needed.

From the `android-ar-tracer/` directory:

```bash
# Generate the Gradle wrapper jar the first time (one-shot, requires a system Gradle ≥ 8.x):
gradle wrapper --gradle-version 8.7

# Debug build:
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release build (uses the debug signing config by default until you wire up a keystore):
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk

# Install on a connected device:
./gradlew :app:installDebug
```

If you don't already have a system Gradle, just open the project in Android Studio once — it
will create the wrapper jar (`gradle/wrapper/gradle-wrapper.jar`) automatically and from then on
`./gradlew` works on its own.

> **Signing for release.** For a real release, add a `signingConfigs.release { … }` block in
> `app/build.gradle.kts` and reference it from `buildTypes.release.signingConfig`. Without that,
> `assembleRelease` produces an unsigned APK that has to be signed with `apksigner` before it
> can be installed.

---

## Permissions

| Permission                  | Why                                                               | When asked                                  |
| --------------------------- | ----------------------------------------------------------------- | ------------------------------------------- |
| `CAMERA`                    | Live preview behind the overlay                                   | First launch (with rationale on second ask) |
| `READ_EXTERNAL_STORAGE`     | Loading images on Android 12 and below (Photo Picker handles 13+) | Only on API ≤ 32, and only if needed        |
| `WRITE_EXTERNAL_STORAGE`    | Saving screenshots on Android 9 and below (MediaStore on Q+)      | Only on API ≤ 28                            |

The Photo Picker (Android 13+) and `ContentResolver.openInputStream` are used for image loading,
so on modern devices no storage permission prompt appears at all.

---

## Performance notes

- Imported images are downsampled so the longest edge is ≤ 2048 px and decoded on a background
  executor to keep the camera preview smooth.
- The overlay view re-derives its draw matrix from primitive fields each frame, so there are no
  per-frame allocations.
- `PreviewView` uses **performance** implementation mode (SurfaceView under the hood) for the
  lowest latency on low-end devices.
- Resource shrinking and ProGuard/R8 are enabled for `release`. `bundle` ABI/density/language
  splits keep per-device installs minimal when distributed as an `.aab`.

---

## License

This project is provided as-is. You own what you build with it.
