package io.grovs.service

import android.content.Context
import com.google.gson.GsonBuilder
import io.grovs.api.GrovsApi
import io.grovs.handlers.GrovsContext
import io.grovs.BuildConfig
import io.grovs.model.AppDetails
import io.grovs.model.AuthenticationResponse
import io.grovs.model.DebugLogger
import io.grovs.model.DeeplinkDetails
import io.grovs.model.ErrorMessage
import io.grovs.model.Event
import io.grovs.model.GenerateLinkRequest
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.GetDeviceResponse
import io.grovs.model.LogLevel
import io.grovs.model.UpdateAttributesRequest
import io.grovs.model.notifications.MarkNotificationAsReadRequest
import io.grovs.model.notifications.NotificationsRequest
import io.grovs.model.notifications.NotificationsResponse
import io.grovs.model.notifications.NumberOfUnreadNotificationsResponse
import io.grovs.utils.GVRetryResult
import io.grovs.utils.LSJsonDateTypeAdapterFactory
import io.grovs.utils.LSResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

class GrovsService(val context: Context, val apiKey: String, val grovsContext: GrovsContext) {
    private val grovsApi: GrovsApi
    private val appDetails = grovsContext.getAppDetails(context = context)
    private val userAgent = grovsContext.getUserAgent(context = context)
    private val gson = GsonBuilder().setLenient().registerTypeAdapterFactory(
        LSJsonDateTypeAdapterFactory()
    ).create()
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
    }

    init {
        grovsApi = getRetrofit().create(GrovsApi::class.java)
    }

    suspend fun payloadFor(@Body request: AppDetails): LSResult<DeeplinkDetails> {
        DebugLogger.instance.log(LogLevel.INFO, "Fetching payload for device")

        var retryCount = 0
        while (true) {
            try {
                val response = grovsApi.payloadFor(request)
                if (response.isSuccessful) {
                    val body = response.body()
                    body?.let {
                        DebugLogger.instance.log(LogLevel.INFO, "Fetching payload for device - Received payload")
                        return LSResult.Success(it)
                    }
                }

                response.errorBody()?.string()?.let { responseString ->
                    val error = gson.fromJson(responseString, ErrorMessage::class.java)
                    DebugLogger.instance.log(LogLevel.INFO, "Fetching payload - Failed. ${error.error}")

                    return LSResult.Error(java.io.IOException("Failed to fetch the payload. Reason: $response"))
                }
            } catch (e: Exception) {}

            delay(if (retryCount < EAGER_RETRY_COUNT) EAGER_RETRY_FALLBACK_TIME else RETRY_FALLBACK_TIME)
            retryCount++
        }
    }

    suspend fun payloadWithLinkFor(@Body request: AppDetails): LSResult<DeeplinkDetails> {
        DebugLogger.instance.log(LogLevel.INFO, "Fetching payload for device")

        var retryCount = 0
        while (true) {
            try {
                val response = grovsApi.payloadWithLinkFor(request)
                if (response.isSuccessful) {
                    val body = response.body()
                    body?.let {
                        DebugLogger.instance.log(LogLevel.INFO, "Fetching payload for device - Received payload")
                        return LSResult.Success(it)
                    }
                }

                response.errorBody()?.string()?.let { responseString ->
                    val error = gson.fromJson(responseString, ErrorMessage::class.java)
                    DebugLogger.instance.log(LogLevel.INFO, "Fetching payload - Failed. ${error.error}")

                    return LSResult.Error(java.io.IOException("Failed to fetch the payload. ${error.error}"))
                }
            } catch (e: Exception) {}

            delay(if (retryCount < EAGER_RETRY_COUNT) EAGER_RETRY_FALLBACK_TIME else RETRY_FALLBACK_TIME)
            retryCount++
        }
    }

    /// Authenticates the app.
    ///
    /// - Parameters:
    ///   - appDetails: Details of the app.
    fun authenticate(appDetails: AppDetails): Flow<GVRetryResult<AuthenticationResponse>> = flow {
        DebugLogger.instance.log(LogLevel.INFO, "Authenticate")

        var retryCount = 0
        while (true) {
            try {
                val response = grovsApi.authenticate(appDetails)
                if (response.isSuccessful) {
                    val body = response.body()
                    body?.let {
                        DebugLogger.instance.log(LogLevel.INFO, "Authenticate - Success")
                        emit(GVRetryResult.Success(it))
                        return@flow
                    }
                }

                response.errorBody()?.string()?.let { responseString ->
                    val error = gson.fromJson(responseString, ErrorMessage::class.java)
                    DebugLogger.instance.log(LogLevel.INFO, "Authenticate - Failed. ${error.error}")

                    emit(GVRetryResult.Error(java.io.IOException("Failed to authenticate. ${error.error}")))
                    return@flow
                }
            } catch (e: Exception) {
                if (e.javaClass.name == "kotlinx.coroutines.flow.internal.AbortFlowException") {
                    throw  e
                }
            }

            emit(GVRetryResult.Retrying(retryCount))
            delay(if (retryCount < EAGER_RETRY_COUNT) EAGER_RETRY_FALLBACK_TIME else RETRY_FALLBACK_TIME)
            retryCount++
        }
    }

    suspend fun generateLink(title: String?, subtitle: String?, imageURL: String?, data: Map<String, Serializable>?, tags: List<String>?): LSResult<GenerateLinkResponse> {
        try {
            val stringData = gson.toJson(data)
            val stringTags = gson.toJson(tags)
            val request = GenerateLinkRequest(title = title,
                subtitle = subtitle,
                imageUrl =  imageURL,
                data = stringData,
                tags = stringTags)
            val response = grovsApi.generateLink(request)
            if (response.isSuccessful) {
                val body = response.body()
                body?.let {
                    return LSResult.Success(it)
                }
            }

            val error = gson.fromJson(response.errorBody()!!.string(), ErrorMessage::class.java)

            DebugLogger.instance.log(LogLevel.INFO, "Generate link - Failed. ${error.error}")

            return LSResult.Error(java.io.IOException("Failed to generate link. ${error.error}"))
        } catch (e: Exception) {
            return LSResult.Error(e)
        }
    }

    /// Adds an event.
    ///
    /// - Parameters:
    ///   - event: The event to add.
    ///   - completion: A closure indicating the success or failure of the operation.
    suspend fun addEvent(event: Event): LSResult<Boolean> {
        try {
            DebugLogger.instance.log(LogLevel.INFO, "Add event - $event")
            val response = grovsApi.addEvent(event)
            if (response.isSuccessful) {
                val body = response.body()
                body?.let {
                    DebugLogger.instance.log(LogLevel.INFO, "Add event - Successful - $event")

                    return LSResult.Success(true)
                }
            }

            val error = gson.fromJson(response.errorBody()!!.string(), ErrorMessage::class.java)

            DebugLogger.instance.log(LogLevel.INFO, "Add event - Failed - $event ${error.error}")

            return LSResult.Error(java.io.IOException("Failed to log the event. ${error.error}"))
        } catch (e: Exception) {
            return LSResult.Error(e)
        }
    }

    suspend fun updateAttributes(identifier: String? = null, attributes: Map<String, Any>? = null, pushToken: String? = null): LSResult<Boolean> {
        DebugLogger.instance.log(LogLevel.INFO, "Set attributes - $identifier $attributes push token: $pushToken")

        var retryCount = 0
        while (true) {
            try {
                val request = UpdateAttributesRequest( sdkIdentifier = identifier,
                    sdkAttributes = attributes,
                    pushToken = pushToken)
                val response = grovsApi.updateAttributes(request)
                if (response.isSuccessful) {
                    val body = response.body()
                    body?.let {
                        DebugLogger.instance.log(LogLevel.INFO, "Set attributes - Successful - $identifier $attributes")

                        return LSResult.Success(true)
                    }
                }

                response.errorBody()?.string()?.let { responseString ->
                    val error = gson.fromJson(responseString, ErrorMessage::class.java)
                    DebugLogger.instance.log(LogLevel.INFO, "Set attributes - Failed. ${error.error}")

                    return LSResult.Error(java.io.IOException("Failed to set attributes. ${error.error}"))
                }
            } catch (e: Exception) { }

            delay(if (retryCount < EAGER_RETRY_COUNT) EAGER_RETRY_FALLBACK_TIME else RETRY_FALLBACK_TIME)
            retryCount++
        }
    }

    fun getDeviceFor(vendorId: String): Flow<GVRetryResult<GetDeviceResponse>> = flow {
        DebugLogger.instance.log(LogLevel.INFO, "Getting device last seen")

        var retryCount = 0
        while (true) {
            try {
                val response = grovsApi.getDeviceFor(vendorId)
                if (response.isSuccessful) {
                    val body = response.body()
                    body?.let {
                        DebugLogger.instance.log(LogLevel.INFO, "Getting device last seen - Successful")

                        emit(GVRetryResult.Success(it))
                        return@flow
                    }
                }

                response.errorBody()?.string()?.let { responseString ->
                    val error = gson.fromJson(responseString, ErrorMessage::class.java)
                    DebugLogger.instance.log(LogLevel.INFO, "Getting device last seen - Failed. ${error.error}")

                    emit(GVRetryResult.Error(java.io.IOException("Failed to get device last seen. ${error.error}")))
                    return@flow
                }
            } catch (e: Exception) {
                if (e.javaClass.name == "kotlinx.coroutines.flow.internal.AbortFlowException") {
                    throw e
                }

                DebugLogger.instance.log(LogLevel.INFO, "Getting device last seen - Failed. ${e.message}")
            }

            emit(GVRetryResult.Retrying(retryCount))
            delay(if (retryCount < EAGER_RETRY_COUNT) EAGER_RETRY_FALLBACK_TIME else RETRY_FALLBACK_TIME)
            retryCount++
        }
    }

    suspend fun notifications(page: Int): LSResult<NotificationsResponse> {
        DebugLogger.instance.log(LogLevel.INFO, "Getting all the notifications")

        var retryCount = 0
        while (true) {
            try {
                val request = NotificationsRequest(page = page)
                val response = grovsApi.notifications(request)
                if (response.isSuccessful) {
                    val body = response.body()
                    body?.let {
                        DebugLogger.instance.log(LogLevel.INFO, "Getting all the notifications - Successful")

                        return LSResult.Success(it)
                    }
                }

                response.errorBody()?.string()?.let { responseString ->
                    val error = gson.fromJson(responseString, ErrorMessage::class.java)
                    DebugLogger.instance.log(LogLevel.INFO, "Getting all the notifications - Failed. ${error.error}")

                    return LSResult.Error(java.io.IOException("Failed getting all the notifications. ${error.error}"))
                }
            } catch (e: Exception) {
                if (e.javaClass.name == "kotlinx.coroutines.flow.internal.AbortFlowException") {
                    throw e
                }

                DebugLogger.instance.log(LogLevel.INFO, "Getting device last seen - Failed. ${e.message}")
            }

            delay(if (retryCount < EAGER_RETRY_COUNT) EAGER_RETRY_FALLBACK_TIME else RETRY_FALLBACK_TIME)
            retryCount++
        }
    }

    suspend fun numberOfUnreadNotifications(): LSResult<NumberOfUnreadNotificationsResponse> {
        DebugLogger.instance.log(LogLevel.INFO, "Get unread messages")

        var retryCount = 0
        while (true) {
            try {
                val response = grovsApi.numberOfUnreadNotifications()
                if (response.isSuccessful) {
                    val body = response.body()
                    body?.let {
                        DebugLogger.instance.log(LogLevel.INFO, "Get unread messages - Successful")

                        return LSResult.Success(it)
                    }
                }

                response.errorBody()?.string()?.let { responseString ->
                    val error = gson.fromJson(responseString, ErrorMessage::class.java)
                    DebugLogger.instance.log(LogLevel.INFO, "Get unread messages - Failed. ${error.error}")

                    return LSResult.Error(java.io.IOException("Failed get unread messages. ${error.error}"))
                }
            } catch (e: Exception) { }

            delay(if (retryCount < EAGER_RETRY_COUNT) EAGER_RETRY_FALLBACK_TIME else RETRY_FALLBACK_TIME)
            retryCount++
        }
    }

    suspend fun markNotificationAsRead(notificationId: Int): LSResult<Boolean> {
        DebugLogger.instance.log(LogLevel.INFO, "Mark notification as read")

        var retryCount = 0
        while (true) {
            try {
                val request = MarkNotificationAsReadRequest(notificationId = notificationId)
                val response = grovsApi.markNotificationAsRead(request = request)
                if (response.isSuccessful) {
                    val body = response.body()
                    body?.let {
                        DebugLogger.instance.log(LogLevel.INFO, "Mark notification as read - Successful")

                        return LSResult.Success(true)
                    }
                }

                response.errorBody()?.string()?.let { responseString ->
                    val error = gson.fromJson(responseString, ErrorMessage::class.java)
                    DebugLogger.instance.log(LogLevel.INFO, "Mark notification as read - Failed. ${error.error}")

                    return LSResult.Error(java.io.IOException("Failed to mark notification as read. ${error.error}"))
                }
            } catch (e: Exception) { }

            delay(if (retryCount < EAGER_RETRY_COUNT) EAGER_RETRY_FALLBACK_TIME else RETRY_FALLBACK_TIME)
            retryCount++
        }
    }

    suspend fun notificationsToDisplayAutomatically(): LSResult<NotificationsResponse> {
        DebugLogger.instance.log(LogLevel.INFO, "Notifications to display automatically")

        var retryCount = 0
        while (true) {
            try {
                val response = grovsApi.notificationsToDisplayAutomatically()
                if (response.isSuccessful) {
                    val body = response.body()
                    body?.let {
                        DebugLogger.instance.log(LogLevel.INFO, "Getting notifications to display automatically - Successful")

                        return LSResult.Success(it)
                    }
                }

                response.errorBody()?.string()?.let { responseString ->
                    val error = gson.fromJson(responseString, ErrorMessage::class.java)
                    DebugLogger.instance.log(LogLevel.INFO, "Getting notifications to display automatically - Failed. ${error.error}")

                    return LSResult.Error(java.io.IOException("Failed getting notifications to display automatically. ${error.error}"))
                }
            } catch (e: Exception) { }

            delay(if (retryCount < EAGER_RETRY_COUNT) EAGER_RETRY_FALLBACK_TIME else RETRY_FALLBACK_TIME)
            retryCount++
        }
    }

    private fun getRetrofit(): Retrofit {
        val gson = GsonBuilder().setLenient().registerTypeAdapterFactory(
            LSJsonDateTypeAdapterFactory()
        ).create()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_URL)
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