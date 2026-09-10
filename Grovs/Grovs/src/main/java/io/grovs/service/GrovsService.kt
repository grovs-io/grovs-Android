package io.grovs.service

import android.content.Context
import android.os.Build
import android.os.Parcelable
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import io.grovs.api.GrovsApi
import io.grovs.handlers.GrovsContext
import io.grovs.model.ScreenAlias
import io.grovs.model.ScreenAliasesRequest
import io.grovs.BuildConfig
import io.grovs.model.AppDetails
import io.grovs.model.AuthenticationResponse
import io.grovs.model.BatchEventsRequest
import io.grovs.model.BatchEventsResponse
import io.grovs.model.CustomEvent
import io.grovs.model.CustomLinkRedirect
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.ErrorMessage
import io.grovs.model.Event
import io.grovs.model.GenerateLinkRequest
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.GetDeviceResponse
import io.grovs.model.LinkDetailsRequest
import io.grovs.model.LinkDetailsResponse
import io.grovs.model.LogLevel
import io.grovs.model.UpdateAttributesRequest
import io.grovs.model.events.PaymentEvent
import io.grovs.model.notifications.MarkNotificationAsReadRequest
import io.grovs.model.notifications.NotificationsRequest
import io.grovs.model.notifications.NotificationsResponse
import io.grovs.model.notifications.NumberOfUnreadNotificationsResponse
import io.grovs.utils.AppDetailsHelper
import io.grovs.utils.GVRetryResult
import io.grovs.utils.LSJsonDateTypeAdapterFactory
import io.grovs.utils.LSJsonInstantCompatTypeAdapterFactory
import io.grovs.utils.LSJsonInstantTypeAdapterFactory
import io.grovs.utils.LSResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.IOException
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import java.io.Serializable
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

val nullOnEmptyConverterFactory = object : Converter.Factory() {
    fun converterFactory() = this
    override fun responseBodyConverter(type: Type, annotations: Array<out Annotation>, retrofit: Retrofit) = object :
        Converter<ResponseBody, Any?> {
        val nextResponseBodyConverter = retrofit.nextResponseBodyConverter<Any?>(converterFactory(), type, annotations)
        override fun convert(value: ResponseBody) = if (value.contentLength() != 0L) nextResponseBodyConverter.convert(value) else null
    }
}

// Custom Interceptor to add headers to every request
class HeaderInterceptor(private val headers: ()->Map<String, String>) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest: Request = chain.request()
        val requestBuilder: Request.Builder = originalRequest.newBuilder()

        // Add each custom header to the request
        for ((key, value) in headers.invoke()) {
            requestBuilder.addHeader(key, value)
        }

        val request: Request = requestBuilder.build()
        return chain.proceed(request)
    }
}

public class CustomRedirects(
    val ios: CustomLinkRedirect? = null,
    val android: CustomLinkRedirect? = null,
    val desktop: CustomLinkRedirect? = null,
) {
}

public class TrackingParams(
    val utmCampaign: String? = null,
    val utmSource: String? = null,
    val utmMedium: String? = null,
) {
}

/**
 * The SDK's HTTP client. Every Retrofit attempt, retries and flow collection included, goes through
 * [requestExecutor]: it belongs to the consent configuration current when this service was built,
 * runs under the calling operation's consent token (or admits one for a direct call), and throws
 * [io.grovs.handlers.ConsentRevokedException] instead of sending, retrying or returning a result
 * once that consent is gone.
 */
class GrovsService(val context: Context, val apiKey: String, val grovsContext: GrovsContext) : IGrovsService {
    private val requestExecutor = ConsentRequestExecutor(grovsContext.consent)
    private val grovsApi: GrovsApi
    private val appDetails: AppDetailsHelper by lazy { grovsContext.getAppDetails(context = context) }
    private val userAgent: String by lazy { grovsContext.getUserAgent(context = context) }
    private val gson = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        GsonBuilder().setLenient()
            .registerTypeAdapterFactory(LSJsonInstantCompatTypeAdapterFactory())
            .registerTypeAdapterFactory(LSJsonDateTypeAdapterFactory())
            .registerTypeAdapterFactory(LSJsonInstantTypeAdapterFactory())
            .create()
    } else {
        GsonBuilder().setLenient()
            .registerTypeAdapterFactory(LSJsonInstantCompatTypeAdapterFactory())
            .registerTypeAdapterFactory(LSJsonDateTypeAdapterFactory())
            .create()
    }
    private val accessKey: String
        get() {
            if (grovsContext.settings.useTestEnvironment) {
                return "test_$apiKey"
            }

            return apiKey
        }

    companion object {
        val EAGER_RETRY_COUNT: Long = 15
        val EAGER_RETRY_FALLBACK_TIME: Long = 5000
        val RETRY_FALLBACK_TIME: Long = 10000

        /** Backend limit for POST events/batch (events_controller.rb MAX_BATCH_SIZE). */
        const val MAX_BATCH_SIZE = 50
    }

    init {
        grovsApi = getRetrofit().create(GrovsApi::class.java)
    }

    /// One admitted attempt whose unexpected failure is reported as an Error, as before. A consent
    /// rejection or a cancellation is never turned into an Error: it ends the call.
    private suspend fun <T : Any> singleResult(block: suspend () -> LSResult<T>): LSResult<T> =
        try {
            requestExecutor.single(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LSResult.Error(e)
        }

    override suspend fun payloadFor(@Body request: AppDetails): LSResult<DeeplinkDetails> {
        DebugLogger.instance.log(LogLevel.INFO, "Fetching payload for device")

        return requestExecutor.retrying<LSResult<DeeplinkDetails>>("Fetching payload") {
            val response = grovsApi.payloadFor(request)
            if (response.isSuccessful) {
                val body = response.body()
                body?.let {
                    DebugLogger.instance.log(LogLevel.INFO, "Fetching payload for device - Received payload")
                    return@retrying LSResult.Success(it)
                }
            }

            response.errorBody()?.string()?.let { responseString ->
                val error = gson.fromJson(responseString, ErrorMessage::class.java)
                DebugLogger.instance.log(LogLevel.INFO, "Fetching payload - Failed. ${error.error}")

                LSResult.Error(java.io.IOException("Failed to fetch the payload. Reason: $response"))
            }
        }
    }

    override suspend fun payloadWithLinkFor(@Body request: AppDetails): LSResult<DeeplinkDetails> {
        DebugLogger.instance.log(LogLevel.INFO, "Fetching payload for device")

        return requestExecutor.retrying<LSResult<DeeplinkDetails>>("Fetching payload") {
            val response = grovsApi.payloadWithLinkFor(request)
            if (response.isSuccessful) {
                val body = response.body()
                body?.let {
                    DebugLogger.instance.log(LogLevel.INFO, "Fetching payload for device - Received payload")
                    return@retrying LSResult.Success(it)
                }
            }

            response.errorBody()?.string()?.let { responseString ->
                val error = gson.fromJson(responseString, ErrorMessage::class.java)
                DebugLogger.instance.log(LogLevel.INFO, "Fetching payload - Failed. ${error.error}")

                LSResult.Error(java.io.IOException("Failed to fetch the payload. ${error.error}"))
            }
        }
    }

    override suspend fun clipboardStatus(): LSResult<Boolean> {
        DebugLogger.instance.log(LogLevel.INFO, "Clipboard status")

        return singleResult {
            val response = grovsApi.clipboardStatus()
            val active = response.body()?.clipboardActive
            if (response.isSuccessful && active != null) {
                DebugLogger.instance.log(LogLevel.INFO, "Clipboard status - Active: $active")
                LSResult.Success(active)
            } else {
                DebugLogger.instance.log(LogLevel.INFO, "Clipboard status - Failed (${response.code()})")
                LSResult.Error(java.io.IOException("Failed to fetch clipboard status (${response.code()})."))
            }
        }
    }

    /// Authenticates the app.
    ///
    /// - Parameters:
    ///   - appDetails: Details of the app.
    override fun authenticate(appDetails: AppDetails): Flow<GVRetryResult<AuthenticationResponse>> =
        requestExecutor.retryingFlow<AuthenticationResponse>("Authenticate") {
            DebugLogger.instance.log(LogLevel.INFO, "Authenticate")

            val response = grovsApi.authenticate(appDetails)
            if (response.isSuccessful) {
                val body = response.body()
                body?.let {
                    DebugLogger.instance.log(LogLevel.INFO, "Authenticate - Success")
                    return@retryingFlow GVRetryResult.Success(it)
                }
            }

            response.errorBody()?.string()?.let { responseString ->
                val error = gson.fromJson(responseString, ErrorMessage::class.java)
                DebugLogger.instance.log(LogLevel.INFO, "Authenticate - Failed. ${error.error}")

                GVRetryResult.Error(java.io.IOException("Failed to authenticate. ${error.error}"))
            }
        }

    override suspend fun generateLink(title: String?,
                             subtitle: String?,
                             imageURL: String?,
                             data: Map<String, Serializable>?,
                             tags: List<String>?,
                             customRedirects: CustomRedirects?,
                             showPreviewIos: Boolean?,
                             showPreviewAndroid: Boolean?,
                             copyToClipboardIos: Boolean?,
                             copyToClipboardAndroid: Boolean?,
                             tracking: TrackingParams?): LSResult<GenerateLinkResponse> = singleResult {
        val stringData = gson.toJson(data)
        val stringTags = gson.toJson(tags)
        val request = GenerateLinkRequest(title = title,
            subtitle = subtitle,
            imageUrl =  imageURL,
            data = stringData,
            tags = stringTags,
            iosCustomRedirect = customRedirects?.ios,
            androidCustomRedirect = customRedirects?.android,
            desktopCustomRedirect = customRedirects?.desktop,
            showPreviewIos = showPreviewIos,
            showPreviewAndroid = showPreviewAndroid,
            copyToClipboardIos = copyToClipboardIos,
            copyToClipboardAndroid = copyToClipboardAndroid,
            trackingCampaign = tracking?.utmCampaign,
            trackingMedium = tracking?.utmMedium,
            trackingSource = tracking?.utmSource)
        val response = grovsApi.generateLink(request)
        if (response.isSuccessful) {
            val body = response.body()
            body?.let {
                return@singleResult LSResult.Success(it)
            }
        }

        val error = gson.fromJson(response.errorBody()!!.string(), ErrorMessage::class.java)

        DebugLogger.instance.log(LogLevel.INFO, "Generate link - Failed. ${error.error}")

        LSResult.Error(java.io.IOException("Failed to generate link. ${error.error}"))
    }

    override suspend fun linkDetails(path: String): LSResult<LinkDetailsResponse> = singleResult {
        val request = LinkDetailsRequest(path = path)
        val response = grovsApi.linkDetails(request)
        if (response.isSuccessful) {
            val body = response.body()
            body?.string()?.let {
                try {
                    if (it == "null") {
                        return@singleResult LSResult.Error(java.io.IOException("Invalid link path."))
                    } else {
                        val map: Map<String, Any> =
                            gson.fromJson(it, object : TypeToken<Map<String, Any?>>() {}.type)
                        return@singleResult LSResult.Success(LinkDetailsResponse(link = map))
                    }
                } catch (error: Exception) {
                    DebugLogger.instance.log(LogLevel.INFO, "Link details - Failed. ${error}")
                }
            }
        }

        val error = gson.fromJson(response.errorBody()!!.string(), ErrorMessage::class.java)

        DebugLogger.instance.log(LogLevel.INFO, "Link details - Failed. ${error.error}")

        LSResult.Error(java.io.IOException("Failed to get link details. ${error.error}"))
    }

    override suspend fun addEvents(events: List<Event>): LSResult<BatchEventsResponse> =
        postBatch(events, label = "Add events")

    override suspend fun addCustomEvents(events: List<CustomEvent>): LSResult<BatchEventsResponse> =
        postBatch(events, label = "Add custom events")

    /// One POST events/batch. Never retries; the flush cycles own retrying.
    private suspend fun postBatch(events: List<Any>, label: String): LSResult<BatchEventsResponse> {
        if (events.isEmpty()) return LSResult.Success(BatchEventsResponse(accepted = 0, rejected = 0))
        if (events.size > MAX_BATCH_SIZE) {
            return LSResult.Error(IllegalArgumentException("$label - batch of ${events.size} exceeds $MAX_BATCH_SIZE"))
        }
        return try {
            requestExecutor.single<LSResult<BatchEventsResponse>> {
                DebugLogger.instance.log(LogLevel.INFO, "$label batch - ${events.size} events")
                val response = grovsApi.addEventsBatch(BatchEventsRequest(events))
                if (!response.isSuccessful) {
                    val body = response.errorBody()?.string()
                    DebugLogger.instance.log(LogLevel.INFO, "$label batch - Failed (${response.code()}) $body")
                    return@single LSResult.Error(java.io.IOException("$label batch failed (${response.code()}). $body"))
                }
                // The backend always answers with counts; an empty 2xx body still means "consumed".
                val parsed = response.body() ?: BatchEventsResponse(accepted = events.size, rejected = 0)
                if (parsed.rejected > 0) {
                    DebugLogger.instance.log(LogLevel.ERROR, "$label batch - ${parsed.rejected} events rejected: ${parsed.errors}")
                }
                DebugLogger.instance.log(LogLevel.INFO, "$label batch - Successful, accepted ${parsed.accepted}")
                LSResult.Success(parsed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLogger.instance.log(LogLevel.INFO, "$label batch - Failed: ${e.message}")
            LSResult.Error(e)
        }
    }

    /// Adds an event.
    ///
    /// - Parameters:
    ///   - event: The payment event to add.
    ///   return: A closure indicating the success or failure of the operation.
    override suspend fun addPaymentEvent(event: PaymentEvent): LSResult<Boolean> = singleResult {
        DebugLogger.instance.log(LogLevel.INFO, "Add payment event - $event")
        val response = grovsApi.addPaymentEvent(event)
        if (response.isSuccessful) {
            val body = response.body()
            body?.let {
                DebugLogger.instance.log(LogLevel.INFO, "Add payment event - Successful - $event")

                return@singleResult LSResult.Success(true)
            }
        }

        val error = gson.fromJson(response.errorBody()!!.string(), ErrorMessage::class.java)

        DebugLogger.instance.log(LogLevel.INFO, "Add payment event - Failed - $event ${error.error}")

        LSResult.Error(java.io.IOException("Failed to log the payment event. ${error.error}"))
    }

    override suspend fun updateAttributes(identifier: String?, attributes: Map<String, Any>?, pushToken: String?): LSResult<Boolean> {
        DebugLogger.instance.log(LogLevel.INFO, "Set attributes - $identifier $attributes push token: $pushToken")

        // A newer attribute update supersedes this one by cancelling it; the executor never
        // swallows that cancellation, so the retry loop stays cancellable.
        return requestExecutor.retrying<LSResult<Boolean>>("Set attributes") {
            val request = UpdateAttributesRequest( sdkIdentifier = identifier,
                sdkAttributes = attributes,
                pushToken = pushToken)
            val response = grovsApi.updateAttributes(request)
            if (response.isSuccessful) {
                val body = response.body()
                body?.let {
                    DebugLogger.instance.log(LogLevel.INFO, "Set attributes - Successful - $identifier $attributes")

                    return@retrying LSResult.Success(true)
                }
            }

            response.errorBody()?.string()?.let { responseString ->
                val error = gson.fromJson(responseString, ErrorMessage::class.java)
                DebugLogger.instance.log(LogLevel.INFO, "Set attributes - Failed. ${error.error}")

                LSResult.Error(java.io.IOException("Failed to set attributes. ${error.error}"))
            }
        }
    }

    override fun getDeviceFor(vendorId: String): Flow<GVRetryResult<GetDeviceResponse>> =
        requestExecutor.retryingFlow<GetDeviceResponse>("Getting device last seen") {
            DebugLogger.instance.log(LogLevel.INFO, "Getting device last seen")

            val response = grovsApi.getDeviceFor(vendorId)
            if (response.isSuccessful) {
                val body = response.body()
                body?.let {
                    DebugLogger.instance.log(LogLevel.INFO, "Getting device last seen - Successful")

                    return@retryingFlow GVRetryResult.Success(it)
                }
            }

            response.errorBody()?.string()?.let { responseString ->
                val error = gson.fromJson(responseString, ErrorMessage::class.java)
                DebugLogger.instance.log(LogLevel.INFO, "Getting device last seen - Failed. ${error.error}")

                GVRetryResult.Error(java.io.IOException("Failed to get device last seen. ${error.error}"))
            }
        }

    override suspend fun notifications(page: Int): LSResult<NotificationsResponse> {
        DebugLogger.instance.log(LogLevel.INFO, "Getting all the notifications")

        return requestExecutor.retrying<LSResult<NotificationsResponse>>("Getting all the notifications") {
            val request = NotificationsRequest(page = page)
            val response = grovsApi.notifications(request)
            if (response.isSuccessful) {
                val body = response.body()
                body?.let {
                    DebugLogger.instance.log(LogLevel.INFO, "Getting all the notifications - Successful")

                    return@retrying LSResult.Success(it)
                }
            }

            response.errorBody()?.string()?.let { responseString ->
                val error = gson.fromJson(responseString, ErrorMessage::class.java)
                DebugLogger.instance.log(LogLevel.INFO, "Getting all the notifications - Failed. ${error.error}")

                LSResult.Error(java.io.IOException("Failed getting all the notifications. ${error.error}"))
            }
        }
    }

    override suspend fun numberOfUnreadNotifications(): LSResult<NumberOfUnreadNotificationsResponse> {
        DebugLogger.instance.log(LogLevel.INFO, "Get unread messages")

        return requestExecutor.retrying<LSResult<NumberOfUnreadNotificationsResponse>>("Get unread messages") {
            val response = grovsApi.numberOfUnreadNotifications()
            if (response.isSuccessful) {
                val body = response.body()
                body?.let {
                    DebugLogger.instance.log(LogLevel.INFO, "Get unread messages - Successful")

                    return@retrying LSResult.Success(it)
                }
            }

            response.errorBody()?.string()?.let { responseString ->
                val error = gson.fromJson(responseString, ErrorMessage::class.java)
                DebugLogger.instance.log(LogLevel.INFO, "Get unread messages - Failed. ${error.error}")

                LSResult.Error(java.io.IOException("Failed get unread messages. ${error.error}"))
            }
        }
    }

    override suspend fun markNotificationAsRead(notificationId: Int): LSResult<Boolean> {
        DebugLogger.instance.log(LogLevel.INFO, "Mark notification as read")

        return requestExecutor.retrying<LSResult<Boolean>>("Mark notification as read") {
            val request = MarkNotificationAsReadRequest(notificationId = notificationId)
            val response = grovsApi.markNotificationAsRead(request = request)
            if (response.isSuccessful) {
                val body = response.body()
                body?.let {
                    DebugLogger.instance.log(LogLevel.INFO, "Mark notification as read - Successful")

                    return@retrying LSResult.Success(true)
                }
            }

            response.errorBody()?.string()?.let { responseString ->
                val error = gson.fromJson(responseString, ErrorMessage::class.java)
                DebugLogger.instance.log(LogLevel.INFO, "Mark notification as read - Failed. ${error.error}")

                LSResult.Error(java.io.IOException("Failed to mark notification as read. ${error.error}"))
            }
        }
    }

    override suspend fun notificationsToDisplayAutomatically(): LSResult<NotificationsResponse> {
        DebugLogger.instance.log(LogLevel.INFO, "Notifications to display automatically")

        return requestExecutor.retrying<LSResult<NotificationsResponse>>("Notifications to display automatically") {
            val response = grovsApi.notificationsToDisplayAutomatically()
            if (response.isSuccessful) {
                val body = response.body()
                body?.let {
                    DebugLogger.instance.log(LogLevel.INFO, "Getting notifications to display automatically - Successful")

                    return@retrying LSResult.Success(it)
                }
            }

            response.errorBody()?.string()?.let { responseString ->
                val error = gson.fromJson(responseString, ErrorMessage::class.java)
                DebugLogger.instance.log(LogLevel.INFO, "Getting notifications to display automatically - Failed. ${error.error}")

                LSResult.Error(java.io.IOException("Failed getting notifications to display automatically. ${error.error}"))
            }
        }
    }

    override suspend fun syncScreenAliases(aliases: Map<String, String>): LSResult<Boolean> {
        // An empty map is not a no-op: it is how a caller clears the aliases the backend holds, so
        // it has to go out on the wire rather than being answered locally.
        return singleResult {
            val request = ScreenAliasesRequest(
                screenAliases = aliases.map { ScreenAlias(identifier = it.key, alias = it.value) }
            )
            val response = grovsApi.syncScreenAliases(request)

            if (response.isSuccessful) {
                LSResult.Success(true)
            } else {
                val code = response.code()
                DebugLogger.instance.log(
                    LogLevel.ERROR,
                    "Sync screen aliases - Failed ($code)"
                )
                LSResult.Error(java.io.IOException("Failed to sync screen aliases ($code)."))
            }
        }
    }

    private fun getRetrofit(): Retrofit {
        val gson = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            GsonBuilder().setLenient()
                    .registerTypeAdapterFactory(LSJsonInstantCompatTypeAdapterFactory())
                    .registerTypeAdapterFactory(LSJsonDateTypeAdapterFactory())
                    .registerTypeAdapterFactory(LSJsonInstantTypeAdapterFactory())
            .create()
        } else {
            GsonBuilder().setLenient()
                .registerTypeAdapterFactory(LSJsonInstantCompatTypeAdapterFactory())
                .registerTypeAdapterFactory(LSJsonDateTypeAdapterFactory())
                .create()
        }

        val baseUrl = if (grovsContext.settings.baseURL != null) {
            grovsContext.settings.baseURL!!.trimEnd('/') + "/api/v1/sdk/"
        } else {
            BuildConfig.SERVER_URL
        }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(nullOnEmptyConverterFactory)
            .addConverterFactory(GsonConverterFactory.create(gson))
            // Tags every request with its attempt's consent token for the gate interceptor.
            .callFactory(requestExecutor.callFactory(getOkhttpClient()))
            .build()
    }

    private fun getOkhttpClient(): OkHttpClient {
        val httpLoggingInterceptor = HttpLoggingInterceptor()
        val builder = OkHttpClient.Builder()
        builder.connectTimeout(40, TimeUnit.SECONDS)
        builder.readTimeout(40, TimeUnit.SECONDS)
        builder.writeTimeout(40, TimeUnit.SECONDS)
        // First, so a cancelled or revoked request is refused before any header is built.
        builder.addInterceptor(requestExecutor.gateInterceptor)
        builder.addInterceptor(HeaderInterceptor { headers() })

        if (BuildConfig.NETWORK_LOGGING) {
            /** add logging interceptor at last Interceptor*/
            builder.addInterceptor(httpLoggingInterceptor.apply {
                httpLoggingInterceptor.level = HttpLoggingInterceptor.Level.BODY
            })
        }

        return builder.build()
    }

    private fun headers(): Map<String, String> {
        val customHeaders = mutableMapOf(
            "PROJECT-KEY" to accessKey,
            "IDENTIFIER" to appDetails.applicationId,
            "PLATFORM" to "android",
            "SDK-VERSION" to BuildConfig.SDK_VERSION,
            "User-Agent" to userAgent,
        )

        grovsContext.grovsId?.let {
            customHeaders["LINKSQUARED"] = it
        }

        return customHeaders
    }

}
