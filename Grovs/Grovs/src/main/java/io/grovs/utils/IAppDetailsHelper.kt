package io.grovs.utils

import io.grovs.model.AppDetails

/**
 * Interface for AppDetailsHelper to enable dependency injection and testability.
 * Provides app details information for API requests.
 */
interface IAppDetailsHelper {
    val versionName: String
    val versionCode: Int
    val applicationId: String
    val deviceID: String
    val device: String
    
    /**
     * Creates an AppDetails object with full device and app information.
     * This may involve async operations like getting OpenGL info.
     */
    suspend fun toAppDetails(): AppDetails
}
