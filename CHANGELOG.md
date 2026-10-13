# Changelog

## [3.1.0]

### Added
- Push campaigns: tapping a Grovs push reports the open to Grovs and calls the deep-link listener with the campaign data, merged over the data of its link when it has one.
- Taps before `configure` or before the SDK is authenticated are held. A failed open is retried on the next foreground, up to 3 attempts.
- `Grovs.isGrovsNotification`, `Grovs.handleNotificationOpen`, and `GrovsMessaging` (`handleMessage`, `onNewToken`, `showForegroundNotifications`) for apps with their own `FirebaseMessagingService`.
- Grovs pushes received while the app is open are shown with their image and open the app when tapped.

### Fixed
- A push token set before the SDK was authenticated no longer clears the user identifier and attributes on the server.
- A push token set before `configure` is no longer lost, and token refreshes reach Grovs without app code.
- `push_open` is refused as a custom event name, as the backend reserves it.

### Deprecated
- `FirebaseMessagingService.handleGrovsNotification`: use `GrovsMessaging.handleMessage`.
