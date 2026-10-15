package io.grovs.inapp

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.grovs.BuildConfig
import io.grovs.handlers.ConsentConfiguration
import io.grovs.handlers.GrovsContext
import io.grovs.handlers.ICustomEventsManager
import io.grovs.handlers.ScreenTracker
import io.grovs.handlers.launchOperation
import io.grovs.inapp.model.InAppEvent
import io.grovs.inapp.model.InAppEventType
import io.grovs.inapp.model.InAppJson
import io.grovs.inapp.model.InAppPushPermission
import io.grovs.model.CustomEventRules
import io.grovs.service.IGrovsService
import io.grovs.utils.LSResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The in-app controller's only door to the rest of the SDK. Every entry may come from any thread
 * and runs on the main looper; the network and the event queue run as consent operations.
 */
internal class InAppHost(
    context: Context,
    private val grovsContext: GrovsContext,
    private val configuration: ConsentConfiguration,
    private val service: IGrovsService,
    private val customEvents: ICustomEventsManager,
    private val settings: InAppSettings,
    activity: () -> Activity?,
    private val isAuthenticated: () -> Boolean,
    presenter: (InAppImages) -> InAppPresenter,
    storeKey: String,
    images: InAppImages = InAppImages.forDevice(context, storeKey),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    pushPermission: () -> InAppPushPermission = { InAppDeviceFacts.notificationsPermission(context.applicationContext) },
    unsupportedActions: () -> List<String> = { emptyList() },
    storeExecutor: ExecutorService = InAppQueuedStore.newExecutor(),
) {
    @Volatile
    private var closed = false

    private val store = InAppQueuedStore(InAppFileStore(context.noBackupFilesDir, storeKey), storeExecutor)

    /** The SDK session whose app_open in-app has taken. Main thread. */
    private var openedSession: String? = null

    private val facts = InAppDeviceFacts(
        context = context.applicationContext,
        attributes = { grovsContext.attributes },
        activity = activity,
        pushPermission = pushPermission,
        unsupportedActions = unsupportedActions,
    )

    val controller = InAppController(
        InAppController.Dependencies(
            store = store,
            presenter = presenter(images),
            images = images,
            device = facts::read,
            sync = ::sync,
            report = ::report,
            sessionId = { grovsContext.sessionId },
            canRun = ::canRun,
            isActive = { grovsContext.isForeground && activity() != null },
            schedule = { delayMillis, work -> InAppMainTimer.after(delayMillis, mainHandler, work) },
            sdkVersion = BuildConfig.SDK_VERSION,
            fail = { campaignId, message ->
                InAppLog.info(message)
                settings.listener?.onInAppMessageFailed(campaignId, message)
            },
            track = ::trackFromPage,
            setAttribute = ::setAttributeFromPage,
        )
    ).also { it.settings = settings }

    init {
        images.onStored = { onMain { if (!closed) controller.onImageStored() } }
    }

    fun start() = onMain {
        if (!canRun()) return@onMain
        controller.start()
        openSessionIfNew()
    }

    fun stop() = onMain { controller.stop() }

    /** The manager is gone: in-app never runs again here, and what it shows closes. Queued writes still finish. */
    fun close() {
        closed = true
        onMain {
            controller.stop()
            store.shutdown()
        }
    }

    fun onForeground() = onMain {
        controller.onForeground()
        openSessionIfNew()
    }

    fun onBackground() = onMain { controller.onBackground() }

    /** A custom event or a screen view the SDK accepted, with the host's properties. */
    fun onTracked(name: String, properties: Map<String, Any>?) {
        val event = eventFor(name, properties)
        onMain { controller.onEvent(event) }
    }

    fun onBatchStamps(campaignsChangedAt: String?, userChangedAt: String?) =
        onMain { controller.onBatchStamps(campaignsChangedAt, userChangedAt) }

    fun pause() = onMain { controller.pause() }

    fun resume() = onMain { controller.resume() }

    fun discard(dismissCurrent: Boolean) = onMain { controller.discard(dismissCurrent) }

    fun setExcludedScreens(screens: List<String>) = onMain { controller.setExcludedScreens(screens) }

    /** The presenter finished something the controller does not see, such as the review prompt. */
    fun screenFree() = onMain { if (!closed) controller.showWaiting() }

    private fun canRun(): Boolean =
        !closed && isAuthenticated() && grovsContext.consent.tryAcquire(configuration) != null

    /** Not before in-app runs: an opening it cannot take yet must not be marked as taken. */
    private fun openSessionIfNew() {
        if (!canRun() || !controller.isRunning) return
        val session = grovsContext.sessionId
        if (session == openedSession) return
        openedSession = session
        controller.onAppOpen()
    }

    private fun eventFor(name: String, properties: Map<String, Any>?): InAppEvent {
        // The same limits as the event queue, so an oversized map never reaches a filter.
        val sanitized = CustomEventRules.sanitizeProperties(properties).orEmpty()
        val json = InAppJson.fromHost(sanitized).takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        if (name == ScreenTracker.SCREEN_VIEW_EVENT) {
            return InAppEvent(InAppEventType.SCREEN_VIEW, InAppJson.string(json.get(ScreenTracker.SCREEN_NAME_PROPERTY)), json)
        }
        return InAppEvent(InAppEventType.EVENT, name, json)
    }

    private fun sync(body: JsonObject, done: (ok: Boolean, body: JsonObject?) -> Unit) {
        val answered = AtomicBoolean(false)
        val answer = { ok: Boolean, response: JsonObject? ->
            if (answered.compareAndSet(false, true)) {
                mainHandler.post { if (closed) done(false, null) else done(ok, response) }
            }
        }
        val job = grovsContext.consent.launchOperation(configuration, context = grovsContext.serialDispatcher) {
            val result = try {
                service.inAppSync(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LSResult.Error(e)
            }
            answer(result is LSResult.Success, (result as? LSResult.Success)?.data)
        }
        // A sync refused or cancelled by consent still ends, so the controller is never left syncing.
        if (job == null) answer(false, null) else job.invokeOnCompletion { answer(false, null) }
    }

    private fun report(eventId: String, name: String, properties: JsonObject) {
        val payload = InAppHostValues.toProperties(properties)
        grovsContext.consent.launchOperation(configuration, context = grovsContext.serialDispatcher) {
            customEvents.trackInApp(eventId, name, payload)
        }
    }

    /** grovs.track from an html page: a host custom event, and an event other campaigns can follow. */
    private fun trackFromPage(name: String, properties: JsonObject) {
        val payload = InAppHostValues.toProperties(properties)
        grovsContext.consent.launchOperation(configuration, context = grovsContext.serialDispatcher) {
            if (!CustomEventRules.isValidName(name)) return@launchOperation
            customEvents.track(name, payload, null)
            onTracked(name, payload)
        }
    }

    /** Unlike the host's setter, this leaves the attributes the backend holds free to arrive. */
    private fun setAttributeFromPage(key: String, value: JsonElement) {
        val hostValue = InAppHostValues.toHost(value)
        grovsContext.userAttributes.update { current ->
            val next = current.attributes.orEmpty().toMutableMap()
            if (hostValue == null) next.remove(key) else next[key] = hostValue
            current.copy(attributes = next)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
