package local.oss.chronicle.features.auth

import timber.log.Timber

/**
 * Application-scoped singleton for cross-component communication between
 * [AuthReturnActivity] and [PlexAuthCoordinator].
 *
 * This singleton provides a bridge to allow the deep link handler activity
 * to communicate with the active authentication coordinator, since Activities
 * cannot directly access ViewModels or Fragments.
 *
 * **Lifecycle:**
 * - Coordinator registers when auth flow starts
 * - Coordinator unregisters when auth flow completes or fragment is destroyed
 * - AuthReturnActivity calls [onBrowserReturned] when deep link is received
 */
object AuthCoordinatorSingleton {
    private var coordinator: PlexAuthCoordinator? = null

    /**
     * Registers the active authentication coordinator.
     *
     * Should be called when the auth flow begins or when the fragment containing
     * the coordinator is resumed.
     *
     * @param coordinator The PlexAuthCoordinator instance to register
     */
    fun register(coordinator: PlexAuthCoordinator) {
        Timber.d("Registering PlexAuthCoordinator")
        this.coordinator = coordinator
    }

    /**
     * Unregisters the authentication coordinator.
     *
     * Should be called when the auth flow completes or when the fragment containing
     * the coordinator is paused/destroyed.
     */
    fun unregister() {
        Timber.d("Unregistering PlexAuthCoordinator")
        this.coordinator = null
    }

    /**
     * Notifies the registered coordinator that the browser has returned.
     *
     * Called by [AuthReturnActivity] when the deep link callback is received.
     * This triggers expedited polling in the coordinator to quickly detect
     * successful authentication.
     *
     * If no coordinator is registered, logs a warning and does nothing.
     */
    fun onBrowserReturned() {
        Timber.i("Browser returned, notifying coordinator")
        coordinator?.onBrowserReturned() ?: run {
            Timber.w("No coordinator registered to receive browser return callback")
        }
    }
}
