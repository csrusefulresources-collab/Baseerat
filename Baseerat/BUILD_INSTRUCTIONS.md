# Build instructions

## GitHub Actions
1. Replace the old project in your repository with the contents of this zip
   (delete the old files first; see "Updating from an older version" below).
2. Actions > *Build APK* > Run workflow.
3. Artifacts: `Baseerat-debug-apk` (testing) and `Baseerat-stable-apk` (release build).

The workflow first builds WITH the optional libraries; if that fails it automatically rebuilds
WITHOUT them (`-PoptionalLibs=false`) and prints a warning, so you always get an APK.

Optional signing of the stable APK: repository secrets `BASEERAT_KEYSTORE_BASE64`, `BASEERAT_KS_PASS`,
`BASEERAT_KEY_ALIAS`, `BASEERAT_KEY_PASS`. Without them the stable APK uses the debug key
(installable, but later updates must use the same key).

## Local
JDK 17 + Android SDK 35: `./gradlew assembleDebug` / `./gradlew assembleRelease`.

## Optional native libraries (Urdu OCR and chat models)
Two features need native libraries that are **detected automatically at build time**
(see the top of `app/build.gradle.kts`; the build log prints the result):

| Feature | Library | Log line |
|---|---|---|
| Urdu / Arabic OCR | Tesseract4Android | `Baseerat optional lib - Tesseract (Urdu OCR): ...` |
| Chat with offline models | MediaPipe `tasks-genai` | `Baseerat optional lib - MediaPipe GenAI (chat models): ...` |

* The app accesses both through reflection, so it compiles and runs even if one is missing; the
  Models screen and the Chat tab tell you when an engine is not part of the installed build.
* If a library is not found, set the exact coordinate in `gradle.properties`:
  `tesseractDep=group:artifact:version` / `genaiDep=group:artifact:version`.
* If the build fails because of them, set `optionalLibs=false` in `gradle.properties`.

## Using the features
* **Urdu OCR**: Settings > Models and OCR > download `urd` (and `eng`), engine = Automatic.
* **Chat**: Settings > Models and OCR > Chat model: download a `.task` model (or import one you
  downloaded from Hugging Face / MediaPipe pages), then open the Chat tab.
  Models are 0.5-2 GB; phones with 4 GB RAM or more work best.

## First run on a phone (Android 13+)
App info > three-dot menu > **Allow restricted settings**, then enable *Baseerat* in Accessibility.
The in-app buttons "Turn the service on" (opens the service's own system page) and
"Turn the service off" avoid the system "Shortcut" switch entirely.

## Updating from an older version
Do NOT merge the new files into the old tree. Delete the old project files first (or start from an
empty repository) and then add the contents of this zip; files that no longer exist in the new
version (for example `OverlayService.kt`) break the build if left behind.
