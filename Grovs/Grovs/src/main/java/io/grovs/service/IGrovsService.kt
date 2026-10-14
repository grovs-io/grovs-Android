package io.grovs.service

import com.google.gson.JsonObject
import io.grovs.model.AppDetails
import io.grovs.model.AuthenticationResponse
import io.grovs.model.BatchEventsResponse
import io.grovs.model.CustomEvent
import io.grovs.model.DeeplinkDetails
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.GetDeviceResponse
import io.grovs.model.LinkDetailsResponse
import io.grovs.model.Event
import io.grovs.model.events.PaymentEvent
import io.grovs.utils.GVRetryResult
import io.grovs.utils.LSResult
import kotlinx.coroutines.flow.Flow
import java.io.Serializable

/**
 * Interface for Grovs service operations.
 * This allows for dependency injection and easier testing.
 */
interface IGrovsService {
    
    /**
     * Authenticate the app with the Grovs backend.
     */
    fun authenticate(appDetails: AppDetails): Flow<GVRetryResult<AuthenticationResponse>>
    
    /**
     * Get device information for the given device ID.
     */
    fun getDeviceFor(deviceId: String): Flow<GVRetryResult<GetDeviceResponse>>
    
    /**
     * Get deeplink payload for the app details.
     */
    suspend fun payloadFor(appDetails: AppDetails): LSResult<DeeplinkDetails>
    
    /**
     * Get deeplink payload with specific link.
     */
    suspend fun payloadWithLinkFor(appDetails: AppDetails): LSResult<DeeplinkDetails>

    /**
     * Whether the project had clipboard-enabled link clicks recently.
     * Single attempt: an [LSResult.Error] means "unknown, retry later", never "inactive".
     */
    suspend fun clipboardStatus(): LSResult<Boolean>

    /**
     * Generate a new deep link.
     */
    suspend fun generateLink(
        title: String?,
        subtitle: String?,
        imageURL: String?,
        data: Map<String, Serializable>?,
        tags: List<String>?,
        customRedirects: CustomRedirects?,
        showPreviewIos: Boolean?,
        showPreviewAndroid: Boolean?,
        copyToClipboardIos: Boolean?,
        copyToClipboardAndroid: Boolean?,
        tracking: TrackingParams?
    ): LSResult<GenerateLinkResponse>
    
    /**
     * Get link details for the given path.
     */
    suspend fun linkDetails(path: String): LSResult<LinkDetailsResponse>
    
    /**
     * Update user attributes.
     */
    suspend fun updateAttributes(
        identifier: String?,
        attributes: Map<String, Any>?,
        pushToken: String?
    ): LSResult<Boolean>
    
    /**
     * Send up to [GrovsService.MAX_BATCH_SIZE] system events in one request.
     *
     * Success means the backend consumed the batch; items it rejected are listed in the response
     * and will never be accepted, so the caller removes the whole batch from storage.
     * Error means nothing was consumed; the caller keeps the batch and stops this flush.
     */
    suspend fun addEvents(events: List<Event>): LSResult<BatchEventsResponse>

    /**
     * Add a payment event.
     */
    suspend fun addPaymentEvent(event: PaymentEvent): LSResult<Boolean>

    /** Same contract as [addEvents], for custom events. */
    suspend fun addCustomEvents(events: List<CustomEvent>): LSResult<BatchEventsResponse>

    /**
     * POST in_app/sync. Success only for a 2xx with a JSON object body. One attempt: the in-app
     * controller owns when to try again.
     */
    suspend fun inAppSync(body: JsonObject): LSResult<JsonObject>

    /**
     * Syncs the class-name → friendly-name mapping used for screen-view reporting.
     */
    suspend fun syncScreenAliases(aliases: Map<String, String>): LSResult<Boolean>

    /** Reports that the user opened a push campaign. The backend counts it once per visitor. */
    suspend fun pushOpen(campaignId: String, sessionId: String): LSResult<Boolean>
}
