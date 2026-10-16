# Distributing test builds

Builds go to testers through **Firebase App Distribution**. The app includes no Firebase SDK,
only the uploaded APK.

One-time setup:

1. Create the release key and keep it backed up somewhere safe. If it is lost, testers must
   uninstall to update, which deletes their game history.

   ```sh
   keytool -genkeypair -v -keystore ~/keys/dune-companion.jks -alias dune-companion \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Create `keystore.properties` next to `build.gradle.kts`. It is gitignored.

   ```properties
   storeFile=/home/you/keys/dune-companion.jks
   storePassword=...
   keyAlias=dune-companion
   keyPassword=...
   ```

   Without this file, release builds are signed with the debug key. That is fine for local
   testing but must not be sent to testers.
3. In the [Firebase console](https://console.firebase.google.com), create a project and add an
   Android app with the package `io.github.deepakgsingh.dunecompanion`. Note its App ID
   (`1:…:android:…`). Under App Distribution, create a tester group `trial` and add emails.
4. Install the Firebase CLI and run `firebase login`.

For each release:

```sh
# bump versionCode (and versionName) in build.gradle.kts first — updates need a higher code
./gradlew testDebugUnitTest assembleRelease
apksigner verify --print-certs build/outputs/apk/release/dune-companion-release.apk   # release cert, not "Android Debug"
firebase appdistribution:distribute build/outputs/apk/release/dune-companion-release.apk \
  --app 1:458857310035:android:2c87d4d1498bc67818e3a6 --project dune-companion-trial --groups trial --release-notes-file distribution/release-notes.txt
```

Testers get an email, accept it, and install through the App Tester app. That app also tells
them about updates. `distribution/release-notes.txt` holds the first-run steps, because the
in-app guide can only be reached once the panel is connected.
