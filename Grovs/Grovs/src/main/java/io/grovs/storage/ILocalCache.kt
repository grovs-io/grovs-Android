package io.grovs.storage

import io.grovs.utils.InstantCompat

/**
 * Interface for LocalCache to enable dependency injection and testability.
 */
interface ILocalCache {
    
    /**
     * The number of times the app has been opened.
     */
    var numberOfOpens: Int
    
    /**
     * The timestamp when the app was last resigned (backgrounded).
     */
    var resignTimestamp: InstantCompat?
    
    /**
     * The timestamp when the app was last started.
     */
    var lastStartTimestamp: InstantCompat?
}
