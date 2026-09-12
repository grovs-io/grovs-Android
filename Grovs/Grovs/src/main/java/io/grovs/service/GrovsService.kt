package io.grovs.service

import android.content.Context
import android.os.Build
import android.os.Parcelable
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import io.grovs.api.GrovsApi
import io.grovs.handlers.ConsentToken
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
    @get:JvmSynthetic
    internal val configuration get() = requestExecutor.configuration
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
        /** One attempt plus three retries. Past this, a retryable failure becomes terminal. */
        val MAX_ATTEMPTS: Long = 4
        /** First retry wait. Doubles per retry: 2s, 4s, 8s, plus jitter. */
        val RETRY_BASE_DELAY_MS: Long = 2000

        /** Backend limit for POST events/batch (events_controller.rb MAX_BATCH_SIZE). */
        const val MAX_BATCH_SIZE = 50
    }

    init {
        grovsApi = getRetrofit().create(GrovsApi::class.java)
    }

    /// One admitted attempt. An unexpected failure is returned as an Error; a consent rejection or a
    /// cancellation is rethrown and ends the call.
    private suspend fun <T : Any> singleResult(block: suspend (ConsentToken) -> LSResult<T>): LSResult<T> =
        try {
            requestExecutor.single(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LSResult.Error(e)
        }

    override suspend fun payloadFor(@Body request: AppDetails): LSResult<DeeplinkDetails> {
        DebugLogger.instance.log(LogLevel.INFO, "Fetching payload for device")
        return requestExecutor.retrying("Fetching payload") { token ->
            grovsApi.payloadFor(request, token).toResult("Fetching payload", gson)
        }
    }

    override suspend fun payloadWithLinkFor(@Body request: AppDetails): LSResult<DeeplinkDetails> {
        DebugLogger.instance.log(LogLevel.INFO, "Fetching payload for device")
        return requestExecutor.retrying("Fetching payload") { token ->
            grovsApi.payloadWithLinkFor(request, token).toResult("Fetching payload", gson)
        }
    }

    override suspend fun clipboardStatus(): LSResult<Boolean> {
        DebugLogger.instance.log(LogLevel.INFO, "Clipboard status")

        return singleResult { token ->
            val response = grovsApi.clipboardStatus(token)
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
        requestExecutor.retryingFlow<AuthenticationResponse>("Authenticate") { token ->
            DebugLogger.instance.log(LogLevel.INFO, "Authenticate")
            grovsApi.authenticate(appDetails, token).toRetryResult("Authenticate", gson)
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
                             tracking: TrackingParams?): LSResult<GenerateLinkResponse> = singleResult { token ->
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
        val response = grovsApi.generateLink(request, token)
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

    override suspend fun linkDetails(path: String): LSResult<LinkDetailsResponse> = singleResult { token ->
        val request = LinkDetailsRequest(path = path)
        val response = grovsApi.linkDetails(request, token)
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
            requestExecutor.single<LSResult<BatchEventsResponse>> { token ->
                DebugLogger.instance.log(LogLevel.INFO, "$label batch - ${events.size} events")
                val response = grovsApi.addEventsBatch(BatchEventsRequest(events), token)
                if (!response.isSuccessful) {
                    val body = response.errorBody()?.string()
                    DebugLogger.instance.log(LogLevel.INFO, "$label batch - Failed (${response.code()}) $body")
                    return@single LSResult.Error(HttpStatusException(response.code(), "$label batch failed (${response.code()}). $body"))
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

    /// Adds a payment event. Any 2xx means the backend took it. A non-2xx carries its status, so
    /// the caller can tell a payment the backend refuses from a failure worth retrying.
    override suspend fun addPaymentEvent(event: PaymentEvent): LSResult<Boolean> = singleResult { token ->
        DebugLogger.instance.log(LogLevel.INFO, "Add payment event - $event")
        val response = grovsApi.addPaymentEvent(event, token)
        if (response.isSuccessful) {
            DebugLogger.instance.log(LogLevel.INFO, "Add payment event - Successful - $event")
            return@singleResult LSResult.Success(true)
        }

        val body = response.errorBody()?.string()
        val reason = runCatching { gson.fromJson(body, ErrorMessage::class.java)?.error }.getOrNull() ?: body

        DebugLogger.instance.log(LogLevel.INFO, "Add payment event - Failed (${response.code()}) - $event $reason")

        LSResult.Error(HttpStatusException(response.code(), "Failed to log the payment event (${response.code()}). $reason"))
    }

    override suspend fun updateAttributes(identifier: String?, attributes: Map<String, Any>?, pushToken: String?): LSResult<Boolean> {
        DebugLogger.instance.log(LogLevel.INFO, "Set attributes - $identifier $attributes push token: $pushToken")

        // A newer attribute update supersedes this one by cancelling it; the executor never
        // swallows that cancellation, so the retry loop stays cancellable.
        return requestExecutor.retrying("Set attributes") { token ->
            val request = UpdateAttributesRequest(
                sdkIdentifier = identifier,
                sdkAttributes = attributes,
                pushToken = pushToken,
            )
            grovsApi.updateAttributes(request, token).toStatusResult("Set attributes", gson)
        }
    }

    override fun getDeviceFor(vendorId: String): Flow<GVRetryResult<GetDeviceResponse>> =
        requestExecutor.retryingFlow<GetDeviceResponse>("Getting device last seen") { token ->
            DebugLogger.instance.log(LogLevel.INFO, "Getting device last seen")
            grovsApi.getDeviceFor(vendorId, token).toRetryResult("Getting device last seen", gson)
        }

    override suspend fun notifications(page: Int): LSResult<NotificationsResponse> {
        DebugLogger.instance.log(LogLevel.INFO, "Getting all the notifications")
        return requestExecutor.retrying("Getting all the notifications") { token ->
            grovsApi.notifications(NotificationsRequest(page = page), token)
                .toResult("Getting all the notifications", gson)
        }
    }

    override suspend fun numberOfUnreadNotifications(): LSResult<NumberOfUnreadNotificationsResponse> {
        DebugLogger.instance.log(LogLevel.INFO, "Get unread messages")
        return requestExecutor.retrying("Get unread messages") { token ->
            grovsApi.numberOfUnreadNotifications(token).toResult("Get unread messages", gson)
        }
    }

    override suspend fun markNotificationAsRead(notificationId: Int): LSResult<Boolean> {
        DebugLogger.instance.log(LogLevel.INFO, "Mark notification as read")
        return requestExecutor.retrying("Mark notification as read") { token ->
            grovsApi.markNotificationAsRead(MarkNotificationAsReadRequest(notificationId = notificationId), token)
                .toStatusResult("Mark notification as read", gson)
        }
    }

    override suspend fun notificationsToDisplayAutomatically(): LSResult<NotificationsResponse> {
        DebugLogger.instance.log(LogLevel.INFO, "Notifications to display automatically")
        return requestExecutor.retrying("Notifications to display automatically") { token ->
            grovsApi.notificationsToDisplayAutomatically(token)
                .toResult("Notifications to display automatically", gson)
        }
    }

    override suspend fun syncScreenAliases(aliases: Map<String, String>): LSResult<Boolean> {
        // An empty map is not a no-op: it is how a caller clears the aliases the backend holds, so
        // it has to go out on the wire rather than being answered locally.
        return singleResult { token ->
            val request = ScreenAliasesRequest(
                screenAliases = aliases.map { ScreenAlias(identifier = it.key, alias = it.value) }
            )
            val response = grovsApi.syncScreenAliases(request, token)

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
            .client(getOkhttpClient())
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
