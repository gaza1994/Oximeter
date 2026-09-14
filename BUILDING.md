# Building

## In Android Studio

Clone the repository and open the folder directly (File -> Open, pick the
folder, not a file).
Studio will write its own `local.properties` pointing at your SDK, then sync.
Run with the green arrow, or Build -> Build APK(s).

## From the command line

Needs JDK 21 or newer (25 is fine) and the Android SDK. `local.properties` is
deliberately not in the repository, because it holds a machine-specific SDK
path. Android Studio writes its own on first open; for a command-line build,
create it yourself:

```
sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

Then:

```
gradlew.bat assembleDebug
```

The APK lands in `app\build\outputs\apk\debug\app-debug.apk`.

## What was used for the prebuilt APK

- Android Gradle Plugin 9.4.0, Gradle 9.7.1
- Kotlin 2.4.20. AGP 9 has Kotlin support built in, so there is deliberately no
  `org.jetbrains.kotlin.android` plugin in the build files - applying it is a
  hard error on AGP 9.
- compileSdk 37.2, minSdk 26, targetSdk 36
- Compose BOM 2026.09.00
- JDK 25

Gradle 9.1 and later run on JDK 25, which is what current Android Studio ships
as its bundled runtime, so the Gradle JVM complaint should not appear. If it
ever does, it is set under Settings -> Build, Execution, Deployment -> Build
Tools -> Gradle -> Gradle JDK.

## Continuous integration

`.github/workflows/build.yml` builds a debug APK on every push and attaches it
as a workflow artifact. Releases are cut by hand.

## Signing

There is no release signing configuration in the repository, and no keystore
should ever be committed - `.gitignore` covers `*.jks`, `*.keystore` and
`keystore.properties`. A debug build is fine for sideloading onto your own
devices.
