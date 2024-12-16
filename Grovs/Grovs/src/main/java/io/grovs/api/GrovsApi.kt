package io.grovs.api

import io.grovs.model.AppDetails
import io.grovs.model.AuthenticationResponse
import io.grovs.model.DeeplinkDetails
import io.grovs.model.Event
import io.grovs.model.GenerateLinkRequest
import io.grovs.model.GenerateLinkResponse
import io.grovs.model.GetDeviceResponse
import io.grovs.model.UpdateAttributesRequest
import io.grovs.model.notifications.MarkNotificationAsReadRequest
import io.grovs.model.notifications.NotificationsRequest
import io.grovs.model.notifications.NotificationsResponse
import io.grovs.model.notifications.NumberOfUnreadNotificationsResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface GrovsApi {

    @POST("data_for_device")
    suspend fun payloadFor(@Body request: AppDetails): Response<DeeplinkDetails>

    @POST("data_for_device_and_url")
    suspend fun payloadWithLinkFor(@Body request: AppDetails): Response<DeeplinkDetails>

    @POST("authenticate")
    suspend fun authenticate(@Body request: AppDetails): Response<AuthenticationResponse>

    @POST("create_link")
    suspend fun generateLink(@Body request: GenerateLinkRequest): Response<GenerateLinkResponse>

    @POST("event")
    suspend fun addEvent(@Body request: Event): Response<Unit>

    @POST("visitor_attributes")
    suspend fun updateAttributes(@Body request: UpdateAttributesRequest): Response<Unit>

    @GET("device_for_vendor_id")
    suspend fun getDeviceFor(@Query("vendor_id") page: String): Response<GetDeviceResponse>

    @POST("notifications_for_device")
    suspend fun notifications(@Body request: NotificationsRequest): Response<NotificationsResponse>

    @GET("number_of_unread_notifications")
    suspend fun numberOfUnreadNotifications(): Response<NumberOfUnreadNotificationsResponse>

    @POST("mark_notification_as_read")
    suspend fun markNotificationAsRead(@Body request: MarkNotificationAsReadRequest): Response<Unit>

    @GET("notifications_to_display_automatically")
    suspend fun notificationsToDisplayAutomatically(): Response<NotificationsResponse>
}