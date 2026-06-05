# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

**Personal Expense Tracker** — Android app for recording, categorizing, analyzing, and managing personal income and expenses. Targets students, working adults, freelancers, and small business owners managing personal finances.

Requirements: clean UI, fast performance, **offline support**, future scalability.

## Status

Scaffolded. Single-module Android app at `app/`, offline-first data layer, empty feature set. Stack is the one chosen at scaffold time — see `gradle/libs.versions.toml` for the authoritative versions.

## Tech Stack (confirmed at scaffold time)

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Architecture:** MVVM (ViewModel + StateFlow)
- **Local storage:** Room (offline-first per requirements)
- **DI:** Hilt
- **Async:** Kotlin Coroutines + Flow
- **Build:** Gradle (Kotlin DSL), version catalog at `gradle/libs.versions.toml`
- **minSdk / targetSdk / compileSdk:** 24 / 35 / 35
- **Testing:** JUnit + Compose UI tests + Room in-memory tests
- **Application ID / namespace:** `io.github.jiro.expensetracker` (confirmed owner: Jiro)

## Standard Commands

These assume a standard Gradle/AGP layout (`./gradlew` at the repo root). Run from the project root.

```bash
# Build
./gradlew assembleDebug              # debug APK
./gradlew assembleRelease            # release APK
./gradlew installDebug               # install on connected device/emulator

# Lint & static analysis
./gradlew lint                       # Android Lint
./gradlew lintDebug                  # lint just the debug variant
./gradlew detekt                     # if Detekt is added later

# Tests
./gradlew test                       # all unit tests
./gradlew testDebugUnitTest          # debug-variant unit tests only
./gradlew connectedAndroidTest       # instrumented tests (needs device/emulator)
./gradlew test --tests "io.github.jiro.expensetracker.ExampleUnitTest"  # single test class
./gradlew test --tests "*ExampleUnitTest.addition_isCorrect"           # single test method

# Clean
./gradlew clean
```

## Architecture Intent (big picture, once scaffolded)

The "offline-first" requirement is the load-bearing constraint — it implies:

- **Single source of truth in local DB (Room)**, with the UI observing it via Flow. No network calls in the read path.
- **Repository pattern** between ViewModels and data sources, so a future sync/backup layer can be added without touching ViewModels.
- **Use cases / interactors are optional** — only add them when business logic is reused across multiple ViewModels. Don't pre-emptively layer.
- **Category/transaction data model** is the domain core. Get this right early; reports and analytics compose on top of it.

## Conventions to Establish Early

- One Gradle module to start (`app/`). Split into feature modules only when build times justify it.
- Package layout: `io.github.jiro.expensetracker.{data.{local,repository}, di, domain.model, ui.{theme,<feature>}}`.
- All new dependencies go through `gradle/libs.versions.toml` — no inline version strings in `build.gradle.kts`.
- Add an `.editorconfig` and code-style config (ktlint or Detekt) before the first feature lands.
