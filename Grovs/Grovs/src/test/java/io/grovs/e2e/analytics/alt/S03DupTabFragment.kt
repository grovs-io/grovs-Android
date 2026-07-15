package io.grovs.e2e.analytics.alt

import io.grovs.e2e.analytics.S03BaseFragment

/**
 * Identical simpleName ("S03DupTabFragment") to [io.grovs.e2e.analytics.S03DupTabFragment] but a
 * distinct fully-qualified name. Both resolve to the same screen_name (from simpleName), so a real
 * tab switch between them relies on dedup keying off the FQN to stay distinct.
 */
class S03DupTabFragment : S03BaseFragment()
