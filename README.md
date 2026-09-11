<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://s3.eu-north-1.amazonaws.com/grovs.io/full-white.svg">
    <img src="https://s3.eu-north-1.amazonaws.com/grovs.io/full-black.svg" width="120" alt="Grovs">
  </picture>
</p>

<p align="center">
  <a href="https://github.com/grovs-io/grovs-Android/releases"><img src="https://img.shields.io/github/v/release/grovs-io/grovs-Android?style=flat-square&color=4F46E5" alt="Latest release"/></a>
  <a href="https://central.sonatype.com/artifact/io.grovs/Grovs"><img src="https://img.shields.io/maven-central/v/io.grovs/Grovs?style=flat-square&color=4F46E5&label=maven%20central" alt="Maven Central"/></a>
  <a href="#"><img src="https://img.shields.io/badge/API-21%2B-4F46E5?style=flat-square" alt="API 21+"/></a>
  <a href="#"><img src="https://img.shields.io/badge/kotlin-1.9%2B-4F46E5?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/grovs-io/grovs-Android?style=flat-square&color=4F46E5" alt="MIT License"/></a>
  <a href="https://github.com/grovs-io/grovs-Android/stargazers"><img src="https://img.shields.io/github/stars/grovs-io/grovs-Android?style=flat-square&color=4F46E5" alt="GitHub stars"/></a>
</p>

<p align="center">
  Deep linking, attribution, and smart links for Android.<br/>
  Part of the <a href="https://github.com/grovs-io">Grovs</a> open-source mobile linking platform.
</p>

<p align="center">
  <a href="https://docs.grovs.io/docs/sdk/android/quick-start">Quick Start</a> ·
  <a href="https://docs.grovs.io/docs/sdk/android/api-reference">API Reference</a> ·
  <a href="https://docs.grovs.io">Full Docs</a>
</p>

---

The Grovs Android SDK provides deep linking, app links, link generation, in-app messaging, revenue tracking, and attribution for your Android apps. It supports both Kotlin and Java.

## Features

- **Deep linking & app links** — route users to the right in-app screen, even after install
- **Smart link generation** — create trackable links with metadata, custom redirects, and UTM parameters
- **In-app messaging** — display messages and announcements from the Grovs dashboard
- **Push notifications** — receive push notifications for dashboard-sent messages via Firebase Cloud Messaging
- **Revenue tracking** — log Google Play Billing and custom purchases with automatic attribution
- **User identity** — attach user IDs and attributes for analytics and segmentation
- **Self-hosting support** — point the SDK at your own backend

## Requirements

- Android API 21+ (Android 5.0)
- Kotlin 1.6+ or Java 8+
- Android Studio Arctic Fox+

## Installation

### Gradle

Add the Grovs dependency to your app-level `build.gradle`:

```groovy
dependencies {
    implementation("io.grovs:Grovs:1.2.0")
}
```

## Quick Start

### 1. Initialize the SDK

Configure the SDK in your `Application` class:

```kotlin
import io.grovs.Grovs

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Grovs.configure(this, "your-api-key", useTestEnvironment = false)

        // Optional: enable debug logging
        Grovs.setDebug(LogLevel.INFO)

        // Optional: set user identity for analytics
        Grovs.identifier = "user_id_from_your_app"
        Grovs.attributes = mapOf("name" to "John Doe", "plan" to "premium")
    }
}
```

For self-hosted backends, pass the `baseURL` parameter (domain only — the SDK appends the API path):

```kotlin
Grovs.configure(this, "your-api-key", useTestEnvironment = false, baseURL = "https://your-domain.com")
```

### 2. Forward lifecycle events

In your **launcher activity**, forward lifecycle events to the SDK:

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onStart() {
        super.onStart()
        Grovs.onStart(this)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Grovs.onNewIntent(intent, this)
    }
}
```

### 3. Add intent filters

Add these intent filters to your launcher activity in `AndroidManifest.xml`:

```xml
<!-- Custom URL scheme -->
<intent-filter>
    <data android:scheme="your_app_scheme" android:host="open" />
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
</intent-filter>

<!-- App links (production) -->
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="your_app_host" />
</intent-filter>

<!-- App links (test) -->
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="your_app_test_host" />
</intent-filter>
```

### 4. Handle deep links

Register a listener in your launcher activity to receive deep link events:

```kotlin
Grovs.setOnDeeplinkReceivedListener(this) { deeplinkDetails ->
    // Route the user based on payload data
    val link = deeplinkDetails.link
    val payload = deeplinkDetails.data
    val tracking = deeplinkDetails.tracking

    payload?.get("screen")?.let { screen ->
        navigateTo(screen as String)
    }
}
```

Or use Kotlin Flow for a coroutine-based approach:

```kotlin
lifecycleScope.launch {
    Grovs.Companion::openedLinkDetails.flow.collect { deeplinkDetails ->
        deeplinkDetails?.let {
            Log.d("Grovs", "Link: ${it.link}, data: ${it.data}")
        }
    }
}
```

You can also retrieve details for a specific link path:

```kotlin
// Using a callback
Grovs.linkDetails(path = "/my-link-path", lifecycleOwner = this) { details, error ->
    details?.let { Log.d("Grovs", "Details: $it") }
}

// Using coroutines
val details = Grovs.linkDetails(path = "/my-link-path")
```

### 5. Clipboard-assisted deferred deep linking

When a link with copy-to-clipboard enabled is opened in the browser, the preview page copies the link to the visitor's clipboard before sending them to Google Play. On the first launch after install, if fingerprint matching found nothing, the SDK checks the clipboard and attributes the install deterministically. The deferred link is delivered through the same `GrovsDeeplinkListener` as fingerprint matches. This works out of the box.

Install attribution has a single 25-second waiting budget covering install-referrer lookup, fingerprint matching, and clipboard fallback. Queued lifecycle, purchase, and custom events are held during resolution. If the deadline expires, they may upload without attribution; a later match still applies to events that remain queued. A deep link the app was opened with is attributed to new lifecycle and purchase events immediately and takes precedence over a pending launch lookup; a link the backend rejects yields back to that lookup. Custom-event backfill uses only resolved links from the same session.

Custom events and screen views retain a resolved campaign within their current analytics session, including brief trips to the background. Returning to the foreground after more than 30 minutes starts a new session; its events do not inherit the previous campaign. Already queued events keep their original session and attribution.

On that first launch Android 12+ shows its system "pasted from your clipboard" toast once. The check is skipped entirely for projects with no clipboard-enabled link clicks in the last 48h and for devices whose clipboard holds no web URL, so an organic installer on a project with active clipboard links may see the toast once. Only URLs on accepted Grovs link hosts are sent for matching. Host validation happens after reading the text, so checking another URL can still cause a clipboard read notification.

If your project serves links from a custom domain, list it so the SDK recognizes your links on the clipboard:

```kotlin
Grovs.configure(
    this,
    "your-api-key",
    useTestEnvironment = false,
    baseURL = null,
    autoTrackScreenViews = true,
    clipboardDomains = listOf("links.yourdomain.com")
)
```

## Tracking events

### Custom events

Track custom analytics events with optional properties and tags:

```kotlin
Grovs.track("checkout_completed", properties = mapOf("sku" to "abc", "total" to 42.0), tags = listOf("shop"))
```

Event names must not be blank, and cannot be one of the SDK's reserved names: `view`, `open`, `install`, `reinstall`, `app_open`, `time_spent`, `reactivation`, `user_referred`, `custom`, `screen_view`. Rejected events are logged and dropped.

Properties support strings, booleans, standard numbers, string-keyed maps, lists, and arrays. Nested `Date`, `URL`, and `UUID` values are converted to strings. Sanitization copies nested collections and preserves list order and null entries. A value that can't be represented in JSON is dropped on its own and everything else is kept: a list element is removed from its list, a map entry from its map, and a top-level property from the event. This covers `NaN`, infinity, other types (such as enums, sets, or arbitrary objects), a map with any non-string key (the whole map is dropped), a reference back to an enclosing collection, and a collection nested more than 16 levels deep. Dropped top-level properties are logged. Shared references without cycles are allowed. These rules also apply to `trackScreenView` properties.

Numbers must remain finite when read back as doubles from SDK storage, so `BigInteger` and `BigDecimal` values beyond the `Double` range are dropped like infinity. The whole properties map is omitted if its serialized JSON exceeds 8KB in UTF-8 or input traversal exceeds 8,192 values. Tags are capped at 20 per event, 255 characters each.

### Global tags

```kotlin
Grovs.setGlobalTags(listOf("android", "production"))
```

Merged onto every subsequently tracked event, up to the combined 20-tag cap.

### Screen views

Screen views are tracked **automatically** for Activities and Fragments. When an Activity hosts Fragments, only the **Fragment is reported** (the host Activity's screen view is suppressed) — this matches iOS, which filters out container view controllers. Compose destinations are NOT auto-tracked (the SDK has no Compose dependency) — track those manually.

**Modals** (`DialogFragment`, `BottomSheetDialogFragment`) are treated as overlays, not screens, so they are **not** auto-tracked — the screen underneath stays the current screen. If you want a modal reported as a screen, call `Grovs.trackScreenView("...")` when it opens.

> **Tab switching:** tabs driven by the Navigation component, `ViewPager2`, `setMaxLifecycle`, or `replace()` transactions are tracked automatically (they resume the destination fragment). The legacy `hide()`/`show()` tab pattern does **not** change fragment lifecycle, so those switches are not auto-tracked — use `trackNavigation` (below), `setMaxLifecycle`, or a manual `trackScreenView` in your tab handler.

To disable automatic screen tracking:

```kotlin
Grovs.configure(this, "your-api-key", useTestEnvironment = false, autoTrackScreenViews = false)
```

> **Upgrading from 1.1.x:** auto screen tracking is **on by default** in 1.2.0. Apps that upgrade will start emitting `screen_view` events without any code change. Pass `autoTrackScreenViews = false` to `configure()` to keep the previous behavior.

Track a screen manually — needed for Compose destinations, which the SDK cannot observe:

```kotlin
Grovs.trackScreenView("Checkout", properties = mapOf("step" to 2))
```

#### Jetpack Navigation

For apps using Jetpack Navigation (Navigation-Compose or route-based graphs), hand the SDK your `NavController` and every destination change is tracked automatically — including bottom-navigation tabs, navigation rails, and Compose destinations that the lifecycle-based tracker cannot see:

```kotlin
Grovs.trackNavigation(navController)
```

The screen name is derived from the destination's route, then its `android:label`, then its display name. Call it once per `NavController` (e.g. right after you set the graph); repeat calls on the same controller are ignored, and the SDK holds only a weak reference so it won't leak the hosting Activity/Fragment.

> With **Fragment-based** navigation this can overlap the automatic tracker and emit duplicate screen views under different names. In that case either disable automatic tracking (`autoTrackScreenViews = false`) and rely on `trackNavigation`, or use `trackNavigation` only for Compose/route-based graphs.

Give screens friendly names in the dashboard:

```kotlin
Grovs.setScreenAliases(mapOf("MainActivity" to "Home", "CartFragment" to "Shopping Cart"))
```

### Consent

If your app needs user consent before collecting analytics, configure the SDK disabled and enable it once consent is granted:

```kotlin
Grovs.configure(
    this, "your-api-key", useTestEnvironment = false, baseURL = null,
    autoTrackScreenViews = true, clipboardDomains = null,
    enabled = ConsentStore.hasConsent(),
)

// later
Grovs.setSDK(true)
```

Configuring with `enabled = false` starts no requests, reads no clipboard or device identifiers, and records no events. Install and open are recorded after the SDK is enabled and authenticates. The flag is not persisted: pass the current consent state on every launch.

`setSDK(false)` immediately rejects new collection and invalidates outstanding SDK operations, including their retries and late results. Requests already transmitted may still reach the server. Local writes already admitted can finish safely; this includes acknowledged-event removal, launch bookkeeping, and closing the enabled portion of an engagement interval. Time spent disabled is excluded from engagement. Queued events retain their identity and resume delivery after enable, subject to the existing retention limits. Attribute and alias setters retain their latest desired values while disabled and synchronize when permitted.

Enabling does **not** replay a launch deep link or a cancelled link/notification request. Forward a later `Grovs.onStart(activity)` or `Grovs.onNewIntent(intent, activity)` explicitly to resolve a link. Revoked link-generation/details requests complete with the existing method-specific error; unread-count requests return `null`. Listener completions use the main thread, while caller/lifecycle cancellation still suppresses delivery. Manual message display returns `false` while disabled; existing message UI can remain visible, but its requests and stale updates are blocked.

Pass the current consent state through `configure`'s `enabled` parameter on every launch, and call `setSDK` only after `configure` — the shorter `configure` overloads reset the flag to `true`, so a `setSDK(false)` made before `configure` would be overwritten by the next launch's `configure` call.

## Link Generation

Create smart links with metadata, payload data, and tracking parameters:

```kotlin
Grovs.generateLink(
    title = "Check out this product",
    subtitle = "Limited time offer",
    imageURL = "https://example.com/image.jpg",
    data = mapOf("productId" to "12345", "screen" to "product_detail"),
    tags = listOf("promotion", "share"),
    tracking = TrackingParams(
        utmCampaign = "spring_sale",
        utmSource = "in_app",
        utmMedium = "share_button"
    ),
    lifecycleOwner = this,
    listener = { link, error ->
        link?.let { Log.d("Grovs", "Generated: $it") }
        error?.let { Log.e("Grovs", "Error: $it") }
    }
)
```

Or using coroutines:

```kotlin
lifecycleScope.launch {
    try {
        val link = Grovs.generateLink(
            title = "Check out this product",
            subtitle = "Limited time offer",
            imageURL = "https://example.com/image.jpg",
            data = mapOf("productId" to "12345"),
            tags = listOf("promotion"),
            tracking = TrackingParams(
                utmCampaign = "spring_sale",
                utmSource = "in_app",
                utmMedium = "share_button"
            )
        )
        Log.d("Grovs", "Generated: $link")
    } catch (e: GrovsException) {
        Log.e("Grovs", "Error: ${e.message}")
    }
}
```

To override the project's copy-to-clipboard setting for a single link (used for clipboard-assisted deferred deep linking), pass the platform flags. `null` inherits the project default:

```kotlin
Grovs.generateLink(
    title = "Link Title",
    copyToClipboardIos = true,
    copyToClipboardAndroid = true,
    lifecycleOwner = this,
    listener = { link, _ -> link?.let { Log.d("Grovs", "Generated: $it") } }
)
```

### Custom redirects

Override where a link sends users on each platform:

```kotlin
val redirects = CustomRedirects(
    ios = CustomLinkRedirect(link = "https://example.com/ios-promo"),
    android = CustomLinkRedirect(link = "https://example.com/android-promo"),
    desktop = CustomLinkRedirect(link = "https://example.com/desktop-promo", openAppIfInstalled = false)
)

Grovs.generateLink(
    title = "Special offer",
    data = mapOf("promoId" to "summer25"),
    customRedirects = redirects,
    lifecycleOwner = this,
    listener = { link, error ->
        link?.let { Log.d("Grovs", "Generated: $it") }
    }
)
```

### Share intent

Launch a share intent after generating a link:

```kotlin
Grovs.generateLink(
    title = "Share this",
    data = mapOf("itemId" to "abc"),
    lifecycleOwner = this,
    listener = { link, _ ->
        link?.let {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, it)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Share via"))
        }
    }
)
```

## Messages

> If console messages have **automatic display** enabled in your dashboard, they will appear in your app without any additional integration.

### Push notifications

To receive push notifications for messages sent from the Grovs dashboard:

**1. Add Firebase Cloud Messaging** — If your app doesn't already use Firebase, add your app in the [Firebase Console](https://console.firebase.google.com), download `google-services.json`, and add the dependencies:

```groovy
// project-level build.gradle
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// app-level build.gradle
plugins {
    id("com.google.gms.google-services")
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
```

**2. Upload your Firebase credentials** — In the [Firebase Console](https://console.firebase.google.com), go to **Project Settings → Service Accounts** and generate a new private key. Upload the JSON key file and enter your Firebase Project ID in the [Grovs dashboard](https://app.grovs.io) under **Android Setup → Push Notifications**.

**3. Request notification permission** (Android 13+):

```kotlin
import android.Manifest
import android.os.Build

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
}
```

Add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

**4. Pass the FCM token to Grovs:**

```kotlin
FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
    Grovs.pushToken = token
}
```

Also update the token when it refreshes:

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Grovs.pushToken = token
    }
}
```

Register the service in `AndroidManifest.xml`:

```xml
<service
    android:name=".MyMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

> Push notifications require a physical device or an emulator with Google Play Services.

### Display messages

```kotlin
// Show the messages list as a modal fragment
Grovs.displayMessagesFragment {
    // Fragment was dismissed
}

// Get unread count for badges
lifecycleScope.launch {
    val count = Grovs.numberOfUnreadMessages()
    Log.d("Grovs", "Unread: $count")
}
```

## Revenue Tracking

> Revenue tracking is currently in **beta**.

### Setup

1. Enable revenue tracking in the [Grovs dashboard](https://app.grovs.io) under **Settings → Revenue Tracking**
2. Configure Google Play Real-Time Developer Notifications — the Grovs dashboard provides an automated setup script under **Developers → Android Setup → Revenue**, or you can configure Pub/Sub manually

### Google Play purchases

```kotlin
// In your PurchasesUpdatedListener
override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
        for (purchase in purchases) {
            Grovs.logInAppPurchase(purchase.originalJson)
        }
    }
}
```

> The SDK automatically extracts price, currency, and product info from the purchase JSON. Duplicates are filtered.

### Custom purchases

```kotlin
Grovs.logCustomPurchase(
    type = PaymentEventType.BUY,
    priceInCents = 999,       // $9.99
    currency = "USD",
    productId = "premium_monthly"
)
```

Use `CANCELLATION` and `REFUND` payment event types for cancellations and refunds. For Google Play purchases, these are detected automatically via Real-Time Developer Notifications.

## API Reference

### Properties

| Property | Type | Description |
|---|---|---|
| `useTestEnvironment` | `Boolean` | Enable or disable test environment |
| `identifier` | `String?` | User ID shown in dashboard and reports |
| `attributes` | `Map<String, Any>?` | User attributes for analytics |
| `openedLinkDetails` | `DeeplinkDetails?` | Kotlin Flow emitting deep link details |
| `pushToken` | `String?` | FCM device token for push notifications |

### Key Methods

| Method | Description |
|---|---|
| `configure(application, apiKey, useTestEnvironment, baseURL, autoTrackScreenViews, clipboardDomains, enabled)` | Initialize the SDK (shorter overloads keep the defaults) |
| `setSDK(enabled)` | Enable or disable collection at runtime (consent) |
| `setDebug(level)` | Set logging level (`INFO`, `ERROR`) |
| `onStart(activity)` | Forward launcher activity's `onStart()` |
| `onNewIntent(intent, activity)` | Forward launcher activity's `onNewIntent()` |
| `generateLink(...)` | Generate a smart link (callback or coroutine) |
| `setOnDeeplinkReceivedListener(activity, listener)` | Register deep link listener |
| `linkDetails(path, ...)` | Get details for a link path (callback or coroutine) |
| `displayMessagesFragment(onDismissed)` | Show messages modal fragment |
| `numberOfUnreadMessages()` | Get unread message count (suspend) |
| `logInAppPurchase(originalJson)` | Log a Google Play Billing purchase |
| `logCustomPurchase(type, priceInCents, currency, productId, startDate)` | Log a custom purchase |

Full API reference: [docs.grovs.io/docs/sdk/android/api-reference](https://docs.grovs.io/docs/sdk/android/api-reference)

## Example App

A demo project is available at [grovs-io/grovs-android-example-app](https://github.com/grovs-io/grovs-android-example-app).

## Setup Guides

- [Adding a Gradle Dependency](https://docs.grovs.io/docs/how-to-guides/android/gradle) — add the SDK to your project
- [Getting the Package Name](https://docs.grovs.io/docs/how-to-guides/android/package-name) — find your application ID
- [Getting the SHA-256 Fingerprint](https://docs.grovs.io/docs/how-to-guides/android/sha256-fingerprint) — get your signing certificate fingerprint
- [Adding an Intent Filter](https://docs.grovs.io/docs/how-to-guides/android/intent-filter) — set up deep link intent filters

## Migration Guides

- [Migrate from Firebase Dynamic Links](https://docs.grovs.io/docs/migration-guides/firebase-dynamic-links/android)
- [Migrate from Branch.io](https://docs.grovs.io/docs/migration-guides/branch-io/android)

## Documentation

Full documentation at [docs.grovs.io](https://docs.grovs.io).

## Support

For technical support and inquiries, contact [support@grovs.io](mailto:support@grovs.io).

## License

See [LICENSE](LICENSE) for details.
