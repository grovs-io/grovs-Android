package io.grovs.api

import android.os.Parcelable
import com.google.gson.JsonObject
import io.grovs.handlers.ConsentToken
import io.grovs.model.PushOpenRequest
import io.grovs.model.ScreenAliasesRequest
import io.grovs.model.AppDetails
import io.grovs.model.AuthenticationResponse
import io.grovs.model.BatchEventsRequest
import io.grovs.model.BatchEventsResponse
import io.grovs.model.ClipboardStatusResponse
import io.grovs.model.DeeplinkDetails
import io.grovs.model.GenerateLinkRequest
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.GetDeviceResponse
import io.grovs.model.LinkDetailsRequest
import io.grovs.model.UpdateAttributesRequest
import io.grovs.model.events.PaymentEvent
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * Every call carries the consent token of the attempt making it as its request tag; the consent
 * gate interceptor refuses a request whose token is missing or no longer current.
 */
internal interface GrovsApi {

    @POST("data_for_device")
    suspend fun payloadFor(@Body request: AppDetails, @Tag token: ConsentToken): Response<DeeplinkDetails>

    @POST("data_for_device_and_url")
    suspend fun payloadWithLinkFor(@Body request: AppDetails, @Tag token: ConsentToken): Response<DeeplinkDetails>

    /** Whether the project had clipboard-enabled link clicks recently. Retrofit sends an empty body for a bodiless POST. */
    @POST("clipboard_status")
    suspend fun clipboardStatus(@Tag token: ConsentToken): Response<ClipboardStatusResponse>

    @POST("authenticate")
    suspend fun authenticate(@Body request: AppDetails, @Tag token: ConsentToken): Response<AuthenticationResponse>

    @POST("create_link")
    suspend fun generateLink(@Body request: GenerateLinkRequest, @Tag token: ConsentToken): Response<GenerateLinkResponse>

    @POST("link_details")
    suspend fun linkDetails(@Body request: LinkDetailsRequest, @Tag token: ConsentToken): Response<ResponseBody>

    @POST("events/batch")
    suspend fun addEventsBatch(@Body request: BatchEventsRequest, @Tag token: ConsentToken): Response<BatchEventsResponse>

    @POST("in_app/sync")
    suspend fun inAppSync(@Body body: JsonObject, @Tag token: ConsentToken): Response<JsonObject>

    @POST("add_payment_event")
    suspend fun addPaymentEvent(@Body request: PaymentEvent, @Tag token: ConsentToken): Response<Unit>

    @POST("visitor_attributes")
    suspend fun updateAttributes(@Body request: UpdateAttributesRequest, @Tag token: ConsentToken): Response<Unit>

    @GET("device_for_vendor_id")
    suspend fun getDeviceFor(@Query("vendor_id") page: String, @Tag token: ConsentToken): Response<GetDeviceResponse>

    @POST("screen_aliases")
    suspend fun syncScreenAliases(@Body request: ScreenAliasesRequest, @Tag token: ConsentToken): Response<Unit>

    @POST("push_open")
    suspend fun pushOpen(@Body request: PushOpenRequest, @Tag token: ConsentToken): Response<Unit>
}
