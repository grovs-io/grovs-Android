# Changelog

## [Unreleased]

### Added
- In-app messages: campaigns from the Grovs dashboard show inside the app on app open, on a screen view or on a custom event, with the same rules, limits and reports as on iOS. Ten templates, including custom HTML; star and choice questions with an optional comment; test messages sent from the dashboard.
- `Grovs.setInAppListener` with `GrovsInAppListener` (shown, clicked, dismissed, answered and failed callbacks, plus `shouldShowInAppMessage` to hold one back) and `GrovsInAppListenerAdapter` for Java.
- `Grovs.pauseInAppMessages`, `resumeInAppMessages`, `discardInAppMessages`, `setInAppExcludedScreens` and `setInAppDebugLogging`.

### Notes
- The in-app listener is held strongly. Call `Grovs.setInAppListener(null)` to release it.
- App ratings use Google Play In-App Review only when the app adds `com.google.android.play:review-ktx`. Without it, "Rate the app" campaigns are skipped.
- The SDK does not declare `POST_NOTIFICATIONS`. On Android 13 and later, campaigns that ask for push permission are not supported unless the app declares it.
- New dependency: `androidx.webkit:webkit`.

## [3.1.0]

### Added
- Push campaigns: tapping a Grovs push reports the open to Grovs and calls the deep-link listener with the campaign data, merged over the data of its link when it has one.
- Taps before `configure` or before the SDK is authenticated are held. An open that fails because of a network or server error is retried on the next foreground, up to 3 attempts.
- `Grovs.isGrovsNotification`, `Grovs.handleNotificationOpen`, and `GrovsMessaging` (`handleMessage`, `onNewToken`, `showForegroundNotifications`) for apps with their own `FirebaseMessagingService`.
- Grovs pushes received while the app is open are shown with their image and open the app when tapped.

### Fixed
- A push token set before the SDK was authenticated no longer clears the user identifier and attributes on the server.
- A push token set before `configure` is no longer lost, and token refreshes reach Grovs without app code.
- `push_open` is refused as a custom event name, as the backend reserves it.
- Whole numbers in custom event properties are sent as whole numbers (`4`, not `4.0`).

### Deprecated
- `FirebaseMessagingService.handleGrovsNotification`: use `GrovsMessaging.handleMessage`.
