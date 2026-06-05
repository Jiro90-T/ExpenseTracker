# Expense Tracker

Personal-finance Android app. Offline-first: Room is the single source of truth; UI observes it via Flow.

## Tech stack

- Kotlin 2.0.21, Jetpack Compose + Material 3
- MVVM (ViewModel + StateFlow)
- Room (KSP)
- Hilt
- Coroutines / Flow
- Gradle 8.10.2, AGP 8.7.3, version catalog at `gradle/libs.versions.toml`
- minSdk 24, compileSdk / targetSdk 35

## Open in Android Studio

This project does not run from a bare JDK 8 / no-Android-SDK shell. Open it in **Android Studio Ladybug (2024.2.1) or newer**, which provides JDK 17 and the Android SDK, and will sync the Gradle wrapper automatically.

1. Android Studio → File → Open → select this directory.
2. Wait for the initial Gradle sync.
3. Run the `app` configuration on an emulator (API 24+) or a physical device.

## Headless / CLI build

If you have JDK 17 and `ANDROID_HOME` pointing at an Android SDK:

```bash
./gradlew assembleDebug
./gradlew test
./gradlew connectedAndroidTest   # needs a running emulator / device
```

## Application ID

`io.github.jiro.expensetracker` (owner: Jiro). Lives in `app/build.gradle.kts` as both `namespace` and `applicationId`. To rename (e.g. before publishing), update those two lines, all `package` declarations under `app/src/`, and the directory paths under `app/src/{main,test,androidTest}/java/io/github/`.

## Architecture (big picture)

```
UI (Compose)  ──observes──▶  ViewModel (StateFlow)  ──reads/writes──▶  Repository  ──▶  Room DAO  ──▶  SQLite
```

Offline-first means the read path never touches the network. When a future sync / backup layer is added, it plugs in at the Repository boundary — ViewModels stay unchanged.
