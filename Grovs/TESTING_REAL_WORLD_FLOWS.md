# Real-world SDK regression flows

These tests use the existing JUnit 4/Robolectric, MockWebServer, coroutine-test,
and AndroidJUnitRunner dependencies. Production SDK code is unchanged.

| Flow | Coverage | Required outcome |
| --- | --- | --- |
| 1. Two valid direct links overlap | `LinkAttributionTest`: both response orders, distinct destinations and payloads | The latest user-selected link is returned and owns future attribution. |
| 2. Startup window loses and regains focus | `SystemClipboardTest` and `ClipboardFocusDeviceTest` on API 29+ | The real focus listener wakes within its timeout; after a longer startup modal, referral recovery does not require another `onStart`. |
| 3. Queue survives process death | `ProcessRestartDeviceTest`, orchestrated by the script below | Persisted lifecycle/custom/purchase events retain their identity and session, drain after recovery, and do not create a second INSTALL. |
| 4. Server receives a POST but its acknowledgement is lost | `EventDeliveryE2ETest` using `DISCONNECT_AFTER_REQUEST` | The SDK retains the unacknowledged event and retries the identical body; successful acknowledgement removes it. |
| 5. An offline session precedes a new campaign/session | `LinkAttributionTest` with real persistent stores | Earlier lifecycle, custom and purchase events keep their session and attribution. Only new-session events acquire the new campaign. |
| 6. SDK disabled with queued work | `EventDeliveryE2ETest`: failed-send retry and periodic flush | No new send starts while disabled; the retained queue may drain once re-enabled. |

These are acceptance assertions, not assertions of whatever the SDK happens to
do today. A failing regression is not skipped or weakened to make the build pass.

## JVM tests

From `Grovs/`, with JDK 17 selected:

```sh
./gradlew :Grovs:testDebugUnitTest \
  --tests io.grovs.handlers.LinkAttributionTest \
  --tests io.grovs.e2e.EventDeliveryE2ETest \
  --tests io.grovs.utils.SystemClipboardTest
```

The ordering tests exercise the values returned by the real manager to the
public callback bridge, including the resolved payload and subsequent event
attribution. They do not substitute identical URLs for competing destinations.

## Device focus test

Use an emulator or device on API 29 or later:

```sh
./gradlew :Grovs:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.grovs.flows.ClipboardFocusDeviceTest
```

This uses a real Android startup dialog/window and real `SystemClipboard`, SDK
startup and HTTP lookup. The dialog supplies the focus-loss condition also
encountered during permission prompts; it does not automate permission-controller
buttons or claim to validate their vendor-specific layouts. There is no extra
`Grovs.onStart()` call when focus returns.

## Actual process-restart test

```sh
python3 tools/run-process-restart-test.py --serial emulator-5554
```

The runner installs the library's test APK (`io.grovs.test`), executes the seed
phase against unavailable HTTP endpoints, force-stops that test package without
clearing its data, and executes verification in a new Android process. The test
checks different PIDs and reads the SDK's real SharedPreferences files. This is
not activity recreation or replacing an in-memory fake cache. It begins at the
post-authentication manager boundary, so it does not test authentication recovery.
The two phase methods skip ordinary unordered instrumentation runs; the script
selects each phase explicitly and checks its result. Reports are saved under
`Grovs/build/reports/process-restart/`.

## Backend boundary

Flow 4 tests the SDK's delivery guarantee: the same event ID and payload survive
an ambiguous transport failure. Only a test against the real backend's storage
can prove that repeated IDs produce one analytics record. This suite deliberately
does not implement a deduplicating map inside MockWebServer and count its entries
as evidence that production deduplication works.
