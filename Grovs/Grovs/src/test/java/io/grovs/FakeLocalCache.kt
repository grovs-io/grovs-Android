package io.grovs

import io.grovs.storage.ILocalCache
import io.grovs.utils.InstantCompat

/** In-memory [ILocalCache]. Share one instance across handler instances to simulate a relaunch. */
class FakeLocalCache(
    override var numberOfOpens: Int = 0,
    override var resignTimestamp: InstantCompat? = null,
    override var lastStartTimestamp: InstantCompat? = null,
    override var clipboardFlowPending: Boolean = false,
) : ILocalCache
