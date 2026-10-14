# Changelog

## [Unreleased]

### Added
- In-app messages: campaigns from the Grovs dashboard show inside the app on app open, on a screen view or on a custom event, with the same rules, limits and reports as on iOS. Ten templates, including custom HTML; star and choice questions with an optional comment; test messages sent from the dashboard.
- `Grovs.setInAppListener` with `GrovsInAppListener` (shown, clicked, dismissed, answered and failed callbacks, plus `shouldShowInAppMessage` to hold one back) and `GrovsInAppListenerAdapter` for Java.
- `Grovs.pauseInAppMessages`, `resumeInAppMessages`, `discardInAppMessages`, `setInAppExcludedScreens` and `setInAppDebugLogging`.

### Removed
- The messages inbox screens and the automatic display of dashboard messages. The SDK no longer calls the inbox endpoints. Use in-app messages instead.
- Public classes and helpers that only drove those screens: `io.grovs.handlers.NotificationsManager`, everything in `io.grovs.fragments`, `io.grovs.viewmodels`, `io.grovs.adapters` and `io.grovs.model.notifications`, the generated `io.grovs.databinding` classes, the `Fragment.applySystemBarInsets`, `showProgressBar`, `hideProgressBar` and `NavController.safeNavigate` extensions, `ActivityProvider.requireNotificationsListener`, and the four inbox methods on `IGrovsService`.
- The inbox resources: the `Grovs*` text and button styles (except `GrovsInAppWindow`), `grovs_messages`, `grovs_messages_empty`, the `grovs_*` colors except `grovs_push_notification_icon_tint`, the inbox layouts, navigation graph, slide animations and icons.
- Dependencies the SDK no longer brings in: `com.google.android.material:material`, `androidx.constraintlayout:constraintlayout`, `androidx.navigation:navigation-fragment-ktx` and `androidx.navigation:navigation-ui-ktx` (replaced by `navigation-runtime-ktx`). If your app uses any of them, declare them yourself.

### Deprecated
- `Grovs.displayMessagesFragment(onDismissed)` shows nothing: it calls `onDismissed` once on the main thread.
- `Grovs.numberOfUnreadMessages()` and `numberOfUnreadMessages(lifecycleOwner, onResult)` always yield `0`, the callback once on the main thread. It used to yield `null` when the SDK was not configured, not enabled or offline.
- `Grovs.setOnAutomaticNotificationsListener` and `GrovsNotificationsListener`: the listener is never called.

### Changed
- Screens whose class is named `NotificationsMainFragment`, `NotificationsListFragment`, `NotificationDetailsFragment` or `AutoDisplayedNotificationFragment` are now tracked like any other screen.

### Notes
- The in-app listener is held strongly. Call `Grovs.setInAppListener(null)` to release it.
- App ratings use Google Play In-App Review only when the app adds `com.google.android.play:review-ktx`. Without it, "Rate the app" campaigns are skipped.
- The SDK does not declare `POST_NOTIFICATIONS`. On Android 13 and later, campaigns that ask for push permission are not supported unless the app declares it.
- New dependency: `androidx.webkit:webkit`.
- `BatchEventsResponse` has a new `inApp` field, so its constructor and `copy` signatures changed, and `IGrovsService` has a new `inAppSync` method. This only affects code that constructs or implements these types.
- Known limitation: an HTML message whose script loops forever keeps the app's shared WebView renderer busy after the message is closed.

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
