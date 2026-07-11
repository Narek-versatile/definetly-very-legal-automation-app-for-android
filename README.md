# Legal Automation (Android)

A personal Android app that automates tasks **across other apps and the
browser** — tapping, typing, scrolling, waiting, and **verifying** that each
step actually worked, with a loud **alert** (notification + alarm sound +
vibration + screenshot) when something fails.

It is built for a **non-rooted, modern Android** phone and uses two cooperating
engines:

| Layer | Tech | What it does |
|------|------|--------------|
| **High-level** | `AccessibilityService` | Reads the on-screen UI tree, finds elements **by visible text or resource-id**, taps them, scrolls/swipes, waits for content, and verifies results. Resolution-independent. |
| **Low-level** | **Shizuku** | Runs shell-level input with adb/shell privileges: **launching apps**, **typing text** into fields, and coordinate taps as a fallback. Optional — the app degrades gracefully without it. |

> Automations are defined as **JSON step lists** (the shareable source of
> truth) and edited either through the in-app **visual step builder** or the
> raw **JSON editor**. A record-and-replay feature can be layered on later
> because it would just emit the same JSON.

### Non-Shizuku mode (redundancy)

Shizuku is an **accelerator, never a requirement**. A Home-screen toggle
switches the engine into **non-Shizuku mode**, where every step falls back to
the AccessibilityService:

| Step | With Shizuku | Non-Shizuku fallback |
|------|--------------|----------------------|
| `launch_app` | `monkey`/`am` (works from background) | normal launch intent |
| `input_text` | `input text` keystrokes | accessibility `ACTION_SET_TEXT` on the target or currently-focused field |
| `tap_xy` | `input tap` | gesture dispatch |

The same automations run either way — the toggle just changes *how* the
low-level actions are performed. When Shizuku isn't installed/granted the app
behaves as if the toggle were off.

---

## Continuous integration

`.github/workflows/android.yml` builds the debug APK on every push (JDK 17 +
Android SDK 34) and uploads it as the **`app-debug`** artifact. This is where
the project is actually compiled — grab the APK from a green run's artifacts,
or build locally with the steps below.

---

## Requirements

- Android Studio (Koala or newer) with **Android SDK 34** and **build-tools 34**.
- A device running **Android 10 (API 29)+**. Screenshots-on-failure need
  **Android 11 (API 30)+** (uses the accessibility `takeScreenshot` API).
- **Shizuku** app installed and started (via wireless debugging or a PC) if you
  want app-launch / text-typing via the low-level path. Get it from
  <https://shizuku.rikka.app>.

## Build & install

```bash
git clone <this repo>
cd definetly-very-legal-automation-app-for-android
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/
# or install directly to a connected device:
./gradlew installDebug
```

(If Android Studio prompts, let it create `local.properties` pointing at your
SDK, or set `ANDROID_HOME`.)

## First-run setup on the phone

1. Open the app.
2. **Accessibility service** card → *Open settings* → enable **Legal Automation
   Engine**. This grants the ability to read/tap other apps.
3. **Shizuku** card → start Shizuku (its own app), then *Grant*. Optional but
   enables launching apps in the background and typing into fields via
   `input text`.
4. Allow the notification permission when prompted (needed for failure alerts).

---

## How an automation works

An `Automation` is a name + ordered list of `Step`s. Each step is retried
according to its `retry` policy; the first step that exhausts its retries stops
the run, **captures a screenshot**, and fires the failure alert.

### Step types

| `type` | Fields | Meaning |
|--------|--------|---------|
| `launch_app` | `packageName`, `appLabel` | Launch an app (Shizuku `monkey`, else normal intent). |
| `tap_text` | `text`, `exact` | Tap the first element matching visible text. |
| `tap_id` | `viewId` | Tap element with resource-id (`com.app:id/foo`). |
| `input_text` | `text`, `intoText?`, `intoId?` | Focus a field then type (Shizuku `input text`, else accessibility set-text). |
| `scroll` | `direction` (`UP`/`DOWN`/`LEFT`/`RIGHT`) | Swipe the screen. |
| `wait_for` | `text`, `timeoutMs` | Wait until text appears; fail on timeout. |
| `verify` | `text`, `expectPresent` | Assert text is / isn't on screen — the "did it succeed?" check. |
| `sleep` | `ms` | Fixed pause. |
| `tap_xy` | `x`, `y` | Coordinate tap fallback (fragile). |

Every step also accepts `retry: { attempts, backoffMs }`.

### Example (the seeded sample)

```json
{
  "name": "Example: search in Chrome",
  "steps": [
    { "type": "launch_app", "packageName": "com.android.chrome", "appLabel": "Chrome" },
    { "type": "sleep", "ms": 2500 },
    { "type": "tap_id", "viewId": "com.android.chrome:id/search_box_text" },
    { "type": "input_text", "text": "hello world" },
    { "type": "wait_for", "text": "hello", "timeoutMs": 6000 },
    { "type": "scroll", "direction": "DOWN" },
    { "type": "verify", "text": "hello", "expectPresent": true }
  ]
}
```

> **Finding resource-ids / text:** enable *Developer options → Show taps* and use
> Android Studio's **Layout Inspector**, or Accessibility Scanner, to read the
> `text` and `resource-id` of the elements you want to target.

---

## Alerting on failure

When a step fails after its retries:

1. **Stop** the run immediately.
2. **Screenshot** the current screen and save it under
   `filesDir/logs/screenshots`.
3. **Notify** with a high-priority notification, **alarm sound**, **vibration**,
   and the screenshot inlined.
4. **Log** the run (all steps, pass/fail, messages, screenshot path) — viewable
   in the in-app **Run logs** screen.

---

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── aidl/com/legal/automation/shizuku/IUserService.aidl   # Shizuku shell bridge
└── java/com/legal/automation/
    ├── App.kt                     # tiny service-locator Application
    ├── model/                     # Automation + Step (JSON-serializable)
    ├── data/                      # AutomationStore (+ JSON config)
    ├── service/                   # AutomationAccessibilityService (tap/scroll/verify/screenshot)
    ├── shizuku/                   # ShizukuManager + ShizukuUserService (low-level input)
    ├── engine/                    # AutomationEngine, RunnerService (foreground), AlertManager, logs
    └── ui/                        # Compose screens: Home, Editor, Logs
```

---

## Scope, safety & limitations

- **Consent & control:** the app only acts on **your** device, after **you**
  explicitly enable the accessibility service and (optionally) Shizuku. Nothing
  is sent off the device; logs and screenshots stay in the app's private
  storage.
- **`QUERY_ALL_PACKAGES`** is declared so you can launch apps by package name;
  it's only used to resolve launch intents locally.
- **Fragility:** targeting by text/id is robust; `tap_xy` is a last resort and
  will break across screen sizes.
- **Not yet implemented (roadmap):** record-and-replay capture, an in-app app
  picker for `launch_app`, conditional/branching steps, loops, and scheduling.

This is a **v1 prototype** meant to prove the two-engine mechanic end to end and
give you a real foundation to extend.
