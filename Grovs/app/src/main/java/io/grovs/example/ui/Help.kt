package io.grovs.example.ui

import io.grovs.example.ui.controls.Info

/** What each less obvious control does, in plain words. */
object Help {
    // Home
    val sdkEnabled = Info(
        "SDK enabled",
        "Turns user consent on or off with Grovs.setSDK. When off, the SDK keeps running but sends nothing: no links, events or push data.",
    )
    val pushToken = Info(
        "Push token",
        "The Firebase token the SDK sends to Grovs. Copy it and paste it into the dashboard to send a test push to this phone.",
    )
    val openedFrom = Info(
        "Opened from",
        "The last link that opened the app. The SDK reports a link in two ways: the deep link listener and the openedLinkDetails flow. Both should receive it. \"missed\" means one of them never did, which points to an SDK bug.",
    )
    val identity = Info(
        "Identity",
        "Who this phone is in the dashboard. The identifier is your own user id (Grovs.identifier). Attributes are extra facts about the user, like plan or country (Grovs.attributes).",
    )
    val quickActions = Info(
        "Quick actions",
        "One tap runs the test with the values you last used on its tab. Hold a button to change those values first.",
    )

    // Links
    val inspect = Info(
        "Inspect a link",
        "Paste any Grovs link to see what the backend stores for it: title, data, tags and redirects. Uses Grovs.linkDetails with the link's path.",
    )
    val linkData = Info(
        "Data",
        "Custom values stored with the link. The app that opens the link gets them back in the link details. Values are sent as text, number, true/false or date; tap the type badge to change it.",
    )
    val linkTags = Info("Tags", "Labels for filtering links in the dashboard. They do not change how the link behaves.")
    val utm = Info(
        "UTM tracking",
        "Adds utm_source, utm_medium and utm_campaign to the link so the dashboard can tell which campaign brought each open and install.",
    )
    val customRedirects = Info(
        "Custom redirects",
        "Where the link sends people on each platform, instead of the project's default redirects. Leave it off to use the project settings.",
    )
    val openAppIfInstalled = Info(
        "Open app if installed",
        "On: if the app is installed, the link opens it. Off: the link always goes to the redirect URL, even when the app is installed.",
    )
    val platformOverrides = Info(
        "Platform overrides",
        "Preview shows a landing page with the link's title and image before redirecting. Clipboard copies the link on that page before the store opens, so the app can find it after install (deferred deep linking). \"Default\" uses the project's setting.",
    )

    // Events
    val customEvent = Info(
        "Custom event",
        "Sends an event with Grovs.track. Some names are reserved by the SDK, like screen_view, and the SDK rejects them. \"Send anyway\" sends a reserved name on purpose, to check that it is rejected.",
    )
    val eventPropertiesAndTags = Info(
        "Properties and tags",
        "Properties are values sent with this one event. Tags label the event for filtering, up to 20, each up to 255 characters.",
    )
    val screenView = Info(
        "Screen view",
        "Reports a screen by hand with Grovs.trackScreenView. Activities are also tracked on their own when \"Auto track screens\" is on in Settings. Hold Track to add properties.",
    )
    val globalTags = Info(
        "Global tags",
        "Tags the SDK adds to every event from now on (Grovs.setGlobalTags). Removing all of them clears them.",
    )
    val screenAliases = Info(
        "Screen aliases",
        "Friendly names for screens. The key is the screen's real name, like MainActivity; the value is the name shown in the dashboard.",
    )
    val purchase = Info(
        "Purchase",
        "Logs a payment you track yourself with Grovs.logCustomPurchase. Buy, cancel and refund are the event types. The amount is sent in cents.",
    )
    val startDate = Info(
        "Start date",
        "When the purchase happened. Pick an earlier date to test purchases that are reported late.",
    )
    val playPurchase = Info(
        "Google Play purchase JSON",
        "Paste a real Purchase.originalJson from Google Play Billing. The SDK sends it to Grovs to log the purchase (Grovs.logInAppPurchase).",
    )

    // In-app
    val showMessage = Info(
        "Show a message",
        "Sends an event that can trigger an in-app message. A message shows only if a live campaign in the dashboard uses this event as its trigger.",
    )
    val queue = Info(
        "Queue",
        "Discard waiting drops messages that are queued but not shown yet. Close current also closes the one on screen. Both leave messages paused.",
    )
    val blockAll = Info(
        "Block all messages",
        "Answers \"no\" every time the SDK asks this app whether to show a message. The message is logged as blocked. Not saved across launches.",
    )
    val inAppDebug = Info(
        "Debug logging",
        "Makes the SDK log in-app decisions to Logcat: why a message was or was not shown.",
    )
    val excludedScreens = Info(
        "Excluded screens",
        "Screens where messages never show, by class name, like SecondActivity. Messages wait until you leave the screen.",
    )

    // Settings
    val apiKey = Info("API key", "The key of your Grovs project, from the dashboard. Changes apply after a restart.")
    val environment = Info(
        "Environment",
        "Test sends everything to your project's test environment. Prod sends it to live data. Use the API key that matches.",
    )
    val baseUrl = Info(
        "Base URL",
        "Sends SDK requests to another backend, like a staging server. Must start with http:// or https://. Leave it empty for the SDK default.",
    )
    val clipboardDomains = Info(
        "Clipboard domains",
        "Hosts your links are served from, like links.example.com. Used for deferred deep linking: the SDK only checks clipboard links on these hosts. grovs.link and sqd.link are always accepted.",
    )
    val autoTrackScreens = Info(
        "Auto track screens",
        "When on, the SDK reports a screen view each time an activity or fragment is shown. Turn it off to report screens only by hand.",
    )
    val startEnabled = Info(
        "Start with SDK enabled",
        "The consent state the SDK starts with on launch. Off starts it without sending anything until you turn it on from Home.",
    )
    val debugLevel = Info("Debug level", "How much the SDK writes to Logcat. Info shows everything, Error only failures.")
    val callStyle = Info(
        "Call style",
        "Which SDK API the tests use for links: the suspend (coroutine) functions or the callback ones. Both should behave the same.",
    )

    // Logs
    val linkSources = Info(
        "Link sources",
        "Listener is setOnDeeplinkReceivedListener. Flow is the openedLinkDetails flow. Every opened link should reach both.",
    )
}
