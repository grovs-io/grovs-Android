# Grovs Android SDK

[Grovs](https://grovs.io) is a powerful SDK that enables deep linking and universal linking within your Android applications. This document serves as a guide to integrate and utilize Grovs seamlessly within your project.

<br />

## Installation

### Gradle

Grovs is available as a Gradle artifact, add the below dependency to your `build.gradle`

```
implementation("io.grovs:Grovs:1.0.2")
```

## Configuration

To configure the Grovs SDK within your application, follow these steps:

1. Initialize the SDK with your API key (usually in your `Application` class):

```kotlin
override fun onCreate() {
    super.onCreate()

    Grovs.configure(this, "your-api-key")
}
```

2. In your **launcher activity** add the code for handling incoming links:

```kotlin
override fun onStart() {
    super.onStart()

    Grovs.onStart()
}

override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)

    Grovs.onNewIntent(intent)
}
```

3. Add intent filters to your **launcher activity** in the `AndroidManifest.xml` file to register your app for opening the grovs links:

```xml
<intent-filter>
    <data android:scheme="your_app_scheme" android:host="open" />
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
</intent-filter>

<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="your_app_host" />
</intent-filter>

<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="your_app_test_host" />
</intent-filter>
```

### Usage

Once configured, you can utilize the various functionalities provided by Grovs.

### Handling deeplinks

You can receive deep link events by registering a listener OR by using kotlin coroutines `flow`. Here's how you can implement it:

```kotlin
Grovs.setOnDeeplinkReceivedListener(this) { link, payload ->
    val message = "Got link from listener: $link payload: $payload"
    Log.d("Grovs", message)
}
```

```kotlin
Grovs.Companion::openedLinkDetails.flow.collect { deeplinkDetails ->
    val message = "Got link from flow: ${deeplinkDetails?.link} payload: ${deeplinkDetails?.data}"
    Log.d("Grovs", message)
}
```

### Generating Links

You can generate links using `generateLink` functions, below are some examples:

```kotlin
Grovs.generateLink(title = "Title",
                        subtitle = "Subtitle",
                        imageURL = "url_to_some_image",
                        data = mapOf("param1" to "Value"),
                        tags = listOf("my_tag"),
                        lifecycleOwner = activity,
                        listener = { link, error ->
                        link?.let { link ->
                            Log.d("Grovs", "Generated link: $link")
                        }
                        error?.let { error ->
                            Log.d("Grovs", "Some error occurred: $error")
                        }
})
```

```kotlin
coroutineScope.launch {
    val link = Grovs.generateLink(title = "Title",
                                        subtitle = "Subtitle",
                                        imageURL = "url_to_some_image",
                                        data = mapOf("param1" to "Value"),
                                        tags = listOf("my_tag"))
    Log.d("Grovs", "Generated link: $link")
}
```

### Using messages

IMPORTANT: if console messages have automatic display enabled, they will appear in your app without any additional integration required.

To receive push notifications for the received messages attach the device token to the SDK.

```kotlin
FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
    if (!task.isSuccessful) {
        return@OnCompleteListener
    }

    // Get new FCM registration token
    val token = task.result
    Grovs.pushToken = token
})
```

To get the number of unread messages, for instance if you want to display an unread number bullet, you can use the following SDK method.

```kotlin
coroutineScope.launch {
    val messages = Grovs.numberOfUnreadMessages()
    Log.d("Grovs", "Unread messages: $messages")
}
```

To display the list of the messages on top of everthing else use:

```kotlin
Grovs.displayMessagesFragment {
    // Display has finished.
}
```

## Demo project

You can download and run a demo project [from here](https://github.com/grovs-io/grovs-android-example-app).

## Further Assistance

For further assistance and detailed documentation, refer to the Grovs documentation available at [https://grovs.io/docs](https://docs.grovs.io/s/docs).

For technical support and inquiries, contact our support team at [support@grovs.io](mailto:support@grovs.io).

Thank you for choosing Grovs! We're excited to see what you build with our SDK.

<br />
<br />
Copyright grovs.
