# BinVerse Vision

Android app (Kotlin + Jetpack Compose + CameraX) that turns a phone into
BinVerse's AI vision system: live camera → Groq vision API → structured
waste classification → ESP32 over Wi-Fi. No OpenAI usage anywhere — Groq
only, called directly via its OpenAI-compatible endpoint at
`https://api.groq.com/openai/v1`.

## Project layout

```
BinVerseVision/            Android Studio project (open this folder)
  app/src/main/java/com/binverse/vision/
    AppConfig.kt            All tunable defaults, incl. GROQ_MODEL constant
    BinVerseApp.kt           Application class wiring singletons
    MainActivity.kt          Permission handling + navigation
    camera/CameraManager.kt  CameraX preview + periodic capture + resize/frame-diff
    network/GroqVisionService.kt   Groq /chat/completions vision call
    network/DetectionParser.kt     Robust JSON validation → DetectionResult
    network/Esp32Service.kt        POST /command with timeout/retry
    detection/DetectionController.kt  Orchestration, debounce, cost control
    detection/DetectionHistory.kt
    settings/SettingsManager.kt    Encrypted API key + persisted settings
    ui/                      Compose screens (MainScreen, SettingsScreen)
esp32/binverse_esp32/binverse_esp32.ino   ESP32 firmware (Arduino IDE)
```

## Opening the project

1. Open the `BinVerseVision/` folder in Android Studio (Koala or newer).
2. Let Gradle sync (it will fetch CameraX, Compose, OkHttp, security-crypto).
3. Run on the Tecno Spark 40 (Android 15) or any API 26+ device/emulator
   with a camera. `minSdk = 26`, `targetSdk = compileSdk = 35`.
4. Grant camera permission when prompted.
5. Open the ⚙ Settings screen and paste your Groq API key — it's saved
   into `EncryptedSharedPreferences` (Android Keystore-backed AES-256),
   in its own file, separate from every other setting.

## Groq model

The active model is a single constant: `AppConfig.GROQ_MODEL =
"qwen/qwen3.6-27b"`. This is Groq's current vision-capable model
(image input, JSON mode) — verified against Groq's docs at the time this
was written. Groq rotates/deprecates models fairly often, so if
`console.groq.com/docs/models` shows a newer recommended vision model by
the time you build this, just change that one constant (or the Model ID
field in Settings, which overrides it per-install).

## Build order (matches the 12 phases in the spec)

The code is already structured to support all 12 phases, but if you want
to bring it up on real hardware incrementally, this is the order that
minimizes what you're debugging at once:

1. **Camera preview** — run the app, confirm the live preview renders.
2. **Capture one frame** — tap "ANALYZE NOW" and confirm
   `CameraManager.onFrameCaptured` fires (add a log line if you want to watch it).
3. **Send one image to Groq** — enter a real API key, tap "ANALYZE NOW"
   again, confirm a network call goes out (Logcat / a proxy like Charles).
4. **Validate the structured response** — confirm `DetectionParser`
   produces a `DetectionResult` and doesn't crash on a real Groq reply.
5. **Display on phone** — confirm the dashboard updates with object/confidence/action.
6. **Connect to ESP32** — flash `binverse_esp32.ino`, get its IP from
   Serial Monitor, enter it in Settings, tap "TEST CONNECTION".
7. **Send one test command** — tap "TEST ESP32", confirm the ESP32
   prints it to Serial (it never activates motors from this sketch).
8. **Groq result → ESP32** — tap "ANALYZE NOW" with a real waste object
   in frame, confirm the same JSON now reaches the ESP32.
9. **Automatic periodic detection** — tap "START AUTO-DETECT", confirm
   captures happen on the configured interval (default 1500 ms), not
   every camera frame.
10. **Duplicate protection** — hold a plastic bottle in frame for 10+
    seconds, confirm only one PICKUP is sent per 3-second cooldown
    (configurable in Settings).
11. **Frame pre-filter** — enable it in Settings, point the camera at a
    static scene, confirm the API request counter stops climbing.
12. **Battery/network/API tuning** — adjust capture interval, JPEG
    quality (`AppConfig.UPLOAD_JPEG_QUALITY`/`UPLOAD_MAX_DIMENSION_PX`),
    and the max-requests limit for your actual run duration.

## Safety design (important)

The AI never controls anything directly. The pipeline is strictly:

```
Groq (object_type, confidence, action) → ESP32 → Arduino Mega
                                                     ↓
                             obstacle sensor, distance, mechanism
                             position, bin capacity, e-stop, motor
                             state — only THEN activate mechanism
```

On the Android side specifically:
- Any Groq error, timeout, invalid JSON, or auth failure produces a
  local `safeFallback()` result: `detected=false, action=WAIT`. Nothing
  resembling PICKUP is ever synthesized locally.
- `DetectionParser` clamps `object_type` to the 8 allowed categories and
  `action` to `PICKUP|WAIT|IGNORE`; anything else collapses to
  `unknown`/`WAIT`.
- `enforceConfidenceThreshold()` force-downgrades PICKUP to WAIT if
  confidence is below the configured threshold (default 0.80) —
  independent of whatever Groq itself claimed.
- The ESP32 sketch **does not drive motors**. It only proves the link
  and prints to Serial. Physical actuation is intentionally left to the
  Arduino Mega, which you wire to make its own safety checks — that
  logic isn't part of this Android/ESP32 deliverable by design, per the
  project's own safety requirement that the AI never directly controls
  motors.

## Production API key security

The current build stores the Groq key in `EncryptedSharedPreferences` on
the device — reasonable for a prototype, but the key still exists inside
the APK's sandbox and could theoretically be extracted from a rooted
device or a debuggable build. For a real deployment:

1. Stand up a small backend (Cloud Run, a $5 VPS, an AWS Lambda behind
   API Gateway — anything) that holds the Groq key as a server-side
   secret/environment variable.
2. The backend exposes one endpoint, e.g. `POST /classify`, that accepts
   the JPEG (or a signed upload URL) and forwards the request to Groq
   server-side, returning just the structured JSON back to the phone.
3. The Android app talks to *your* backend instead of `api.groq.com`
   directly. `GroqVisionService` already isolates all Groq-specific
   request-building in one class, so swapping its target URL for your
   backend's `/classify` endpoint is a small, contained change.
4. Add basic auth (a shared device token, or per-device API keys) between
   the phone and your backend so a stolen phone can't run up your Groq
   bill either.
5. Rate-limit and log at the backend so you get the "API requests today"
   and "max requests" controls enforced server-side too, not just
   client-side (the client-side counter in this build resets whenever
   the app process restarts — fine for a prototype, not for production).

## Session frame cap (rate-limit protection)

Each time you tap "START AUTO-DETECT," the app sends at most
`sessionFrameCap` frames to Groq (default 3, configurable in Settings)
before automatically pausing auto-detect again — independent of the
capture interval. This is a hard, low-effort guard against Groq's
free-tier rate limits (HTTP 429) during testing. Manual "ANALYZE NOW"
taps are never counted against this cap. To run another batch, just tap
"START AUTO-DETECT" again — it resets the per-session counter to zero.

## Known prototype limitations

- The request/image counters reset when the app process is killed —
  swap `MutableStateFlow` counters for DataStore-persisted ones if you
  need a true daily counter across restarts.
- The frame pre-filter samples a coarse 16×12 luma grid — cheap and
  fast, but not a substitute for real motion/object detection.
- ESP32 Mode A (join existing Wi-Fi) requires editing `WIFI_SSID` /
  `WIFI_PASSWORD` in the `.ino` file before flashing; there's no
  runtime Wi-Fi provisioning UI on the ESP32 side in this version.
