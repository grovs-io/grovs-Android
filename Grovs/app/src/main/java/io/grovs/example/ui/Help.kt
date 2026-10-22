package io.grovs.example.ui

import io.grovs.example.ui.controls.Info

/** What each less obvious control does, in plain words, for people new to Grovs. */
object Help {
    // Home
    val sdkEnabled = Info(
        "SDK enabled",
        "The Grovs SDK is the library this app uses to talk to Grovs. This switch is the user's consent (Grovs.setSDK). When off, the SDK stays installed but sends nothing to Grovs: no links, events or push data.",
    )
    val pushToken = Info(
        "Push token",
        "Firebase gives every install a token that push notifications are sent to. The SDK passes it to Grovs so you can reach this phone from a dashboard campaign. Copy it to send a test push to just this phone.",
    )
    val openedFrom = Info(
        "Opened from",
        "Grovs links open your app when it is installed, and the SDK hands the link and its data to the app. It does that in two ways: a listener and a flow. This shows the last link that opened the app, and \"missed\" means one of the two never got it.",
    )
    val identity = Info(
        "Identity",
        "Grovs groups links opened, events and purchases by user. The identifier is your own user id, for example from your login (Grovs.identifier). Attributes are facts about the user, like plan or country, shown on their profile in the dashboard.",
    )
    val quickActions = Info(
        "Quick actions",
        "Shortcuts to the most common tests. One tap runs the test with the values you last used on its tab. Hold a button to change the values first.",
    )

    // Links
    val inspect = Info(
        "Inspect a link",
        "Every Grovs link is stored on the Grovs backend with its title, data and settings. Paste a link to load what is stored for it (Grovs.linkDetails). Useful for checking a link made here or in the dashboard.",
    )
    val linkData = Info(
        "Data",
        "Custom values saved inside the link, like a promo code or the id of a product. When someone opens the link, your app gets this data back and can show the right screen. Tap the type badge to send a value as text, number, true/false or date.",
    )
    val linkTags = Info(
        "Tags",
        "Labels for organizing links in the Grovs dashboard, for example by campaign or channel. They do not change how the link behaves.",
    )
    val utm = Info(
        "UTM tracking",
        "UTM parameters are the standard way to name where traffic comes from: source (like newsletter), medium (like email) and campaign. Grovs uses them to show which campaign brought each open and install.",
    )
    val customRedirects = Info(
        "Custom redirects",
        "When someone opens a link without the app installed, Grovs sends them somewhere: by default the store or website set in your project. Turn this on to choose a different place per platform for this one link.",
    )
    val openAppIfInstalled = Info(
        "Open app if installed",
        "On: if the app is installed, the link opens it, and the URL above is only used when it is not. Off: the link always opens the URL above.",
    )
    val platformOverrides = Info(
        "Platform overrides",
        "Per-link versions of two project settings. Preview shows a page with the link's title and image before redirecting. Clipboard copies the link on that page, so after installing, the app can still find which link brought the user. \"Default\" keeps the project's setting.",
    )

    // Events
    val customEvent = Info(
        "Custom event",
        "Events are actions you report from your app, like signup or level_complete (Grovs.track). They appear in the dashboard and can trigger in-app messages. Some names are used by Grovs itself and rejected; \"Send anyway\" checks that.",
    )
    val eventPropertiesAndTags = Info(
        "Properties and tags",
        "Properties are details sent with this one event, like level = 3. Tags are labels for filtering events in the dashboard, up to 20 per event.",
    )
    val screenView = Info(
        "Screen view",
        "Grovs records which screens people visit, for analytics and for in-app message rules. Most screens are reported on their own (see Auto track screens in Settings). This reports one by hand (Grovs.trackScreenView). Hold Track to add properties.",
    )
    val globalTags = Info(
        "Global tags",
        "Tags added to every event this app sends from now on, like beta or region_eu (Grovs.setGlobalTags). Handy for splitting all events by a group of users.",
    )
    val screenAliases = Info(
        "Screen aliases",
        "Automatically tracked screens are named after their code class, like MainActivity. An alias gives a screen a readable name in the dashboard. The key is the class name, the value is the name to show.",
    )
    val purchase = Info(
        "Purchase",
        "Grovs ties purchases to the link or campaign that brought the user, so you can see revenue per link. Use this for payments you handle yourself (Grovs.logCustomPurchase). Buy, cancel and refund are the types; the amount is sent in cents.",
    )
    val startDate = Info(
        "Start date",
        "When the purchase happened. Choose an earlier date to test a purchase that is reported late.",
    )
    val playPurchase = Info(
        "Google Play purchase JSON",
        "For purchases made with Google Play Billing. Paste the purchase's originalJson and the SDK sends it to Grovs to log the purchase (Grovs.logInAppPurchase).",
    )

    // In-app
    val showMessage = Info(
        "Show a message",
        "In-app messages are pop-ups you design in the Grovs dashboard. Each campaign has a trigger: an event name. This sends that event, and a message shows if a live campaign uses it as its trigger.",
    )
    val queue = Info(
        "Queue",
        "Messages wait in a queue until a good moment to show. Discard waiting drops queued messages. Close current also closes the one on screen. Both pause messages; switch to Running to resume.",
    )
    val blockAll = Info(
        "Block all messages",
        "Before showing a message, the SDK asks the app whether it may (shouldShowInAppMessage). On means this app always answers no, and the message is logged as blocked. Not saved across launches.",
    )
    val inAppDebug = Info(
        "Debug logging",
        "Makes the SDK explain its in-app message decisions in Logcat, the Android developer log: why a message was or was not shown.",
    )
    val excludedScreens = Info(
        "Excluded screens",
        "Screens where in-app messages never appear, like checkout. Use the screen's class name, like SecondActivity. Messages wait until the user leaves that screen.",
    )

    // Settings
    val apiKey = Info(
        "API key",
        "Identifies your Grovs project, so the SDK sends data to the right place. Copy it from your project in the Grovs dashboard. Changes apply after a restart.",
    )
    val environment = Info(
        "Environment",
        "Each Grovs project has a test environment and a live (Prod) one, with separate data. Use Test while building so you don't mix test data with real users. Use the API key that matches.",
    )
    val baseUrl = Info(
        "Base URL",
        "The server the SDK talks to. Leave it empty for Grovs' own servers. Set it only to test against another backend, like a staging server; it must start with http:// or https://.",
    )
    val clipboardDomains = Info(
        "Clipboard domains",
        "Deferred deep linking means the app finds the link that brought a user even after a fresh install. One way is through the clipboard. If your links use your own domain, list it here so the SDK trusts it. grovs.link and sqd.link always work.",
    )
    val autoTrackScreens = Info(
        "Auto track screens",
        "When on, the SDK reports a screen view each time an activity or fragment appears, with no extra code. Turn it off to report screens only by hand.",
    )
    val startEnabled = Info(
        "Start with SDK enabled",
        "Whether the user's consent starts as given on each launch. Off starts the SDK without sending anything until you turn it on from Home, like an app waiting for a consent popup.",
    )
    val debugLevel = Info(
        "Debug level",
        "How much the SDK writes to Logcat, the Android developer log. Info shows every step, Error only failures.",
    )
    val callStyle = Info(
        "Call style",
        "The SDK offers each link function in two forms: Kotlin coroutines (suspend) and classic callbacks. Pick which one the tests use; both should give the same result.",
    )

    // Logs
    val linkSources = Info(
        "Link sources",
        "The SDK hands an opened link to the app in two ways: a listener (setOnDeeplinkReceivedListener) and a flow (openedLinkDetails). Apps use one or the other, so every link should reach both.",
    )
}
