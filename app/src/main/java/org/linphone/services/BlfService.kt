package org.linphone.services

import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Content
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Event
import org.linphone.core.EventListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import org.linphone.core.SubscriptionState
import org.linphone.models.blf.BlfKey
import org.linphone.models.blf.BlfKeyStatus
import org.linphone.models.blf.DialogInfoParser
import org.linphone.utils.Log

/**
 * BLF (busy lamp field): SIP dialog event subscriptions (RFC 4235) to numbers on the user's PBX.
 * Ported from the web client (libwebphone lwpBLF and the softphone service's reference-counted keys):
 * - keys are reference counted, so several users of a number share one subscription
 * - a key starts idle, and goes unknown while not subscribed (unregistered, or the subscription ended)
 * - subscriptions are made when the account registers, and retried with backoff if they end
 * - if no NOTIFY arrives within a minute of subscribing, it subscribes again
 *
 * Runs on the core's (main) thread.
 */
object BlfService {
    private const val EVENT = "dialog"
    private const val ACCEPT = "application/dialog-info+xml"
    private const val SUBSCRIBE_EXPIRES = 1800
    private const val RESUBSCRIBE_DELAY_MS = 1_000L
    private const val MAX_RESUBSCRIBE_DELAY_MS = 60_000L
    private const val NOTIFY_TIMEOUT_MS = 60_000L

    private class Subscription(val id: String) {
        var refCount = 0
        var event: Event? = null
        var attempts = 0
        var retry: Runnable? = null
        var watchdog: Runnable? = null
    }

    private val subscriptions = mutableMapOf<String, Subscription>()
    private val keysSubject = BehaviorSubject.createDefault(emptyMap<String, BlfKey>())

    // Number to BLF key, for every number being watched
    val keys: Observable<Map<String, BlfKey>> = keysSubject

    private var started = false

    private val coreListener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String
        ) {
            if (account != core.defaultAccount) return
            when (state) {
                RegistrationState.Ok -> subscribeAll()
                RegistrationState.Cleared, RegistrationState.Failed, RegistrationState.None -> unsubscribeAll()
                else -> {}
            }
        }
    }

    private val eventListener = object : EventListenerStub() {
        override fun onNotifyReceived(event: Event, content: Content?) {
            val subscription = subscriptionFor(event) ?: return
            if (content == null || !"${content.type}/${content.subtype}".contains(ACCEPT)) return
            val body = content.utf8Text
            if (body.isNullOrEmpty()) return

            subscription.attempts = 0
            cancelWatchdog(subscription)
            val result = DialogInfoParser.parse(body)
            updateKey(
                BlfKey(subscription.id, result.status, result.dialogState, result.remoteDisplay)
            )
        }

        override fun onSubscribeStateChanged(event: Event, state: SubscriptionState) {
            val subscription = subscriptionFor(event) ?: return
            if (state != SubscriptionState.Terminated && state != SubscriptionState.Error) return

            Log.w("[BLF] Subscription to ${subscription.id} ended ($state)")
            event.removeListener(this)
            subscription.event = null
            cancelWatchdog(subscription)
            setStatus(subscription.id, BlfKeyStatus.UNKNOWN)
            scheduleResubscribe(subscription)
        }
    }

    private fun start() {
        if (started) return
        started = true
        coreContext.core.addListener(coreListener)
    }

    fun addKey(id: String) {
        start()
        val subscription = subscriptions.getOrPut(id) { Subscription(id) }
        subscription.refCount++
        if (subscription.refCount > 1) return

        updateKey(BlfKey(id, BlfKeyStatus.IDLE))
        if (isRegistered()) subscribe(subscription)
    }

    fun removeKey(id: String) {
        val subscription = subscriptions[id] ?: return
        subscription.refCount--
        if (subscription.refCount > 0) return

        unsubscribe(subscription)
        subscriptions.remove(id)
        keysSubject.onNext(keysSubject.value!! - id)
    }

    private fun isRegistered() = coreContext.core.defaultAccount?.state == RegistrationState.Ok

    private fun subscribeAll() {
        subscriptions.values.forEach { subscribe(it) }
    }

    private fun unsubscribeAll() {
        subscriptions.values.forEach {
            unsubscribe(it)
            setStatus(it.id, BlfKeyStatus.UNKNOWN)
        }
    }

    private fun subscribe(subscription: Subscription) {
        cancelRetry(subscription)
        if (subscription.event != null) return

        val core = coreContext.core
        val domain = core.defaultAccount?.params?.identityAddress?.domain ?: return
        val address = Factory.instance().createAddress("sip:${subscription.id}@$domain") ?: return
        val event = core.createSubscribe(address, EVENT, SUBSCRIBE_EXPIRES)
        if (event == null) {
            Log.e("[BLF] Couldn't create a subscription to ${subscription.id}")
            scheduleResubscribe(subscription)
            return
        }

        event.addCustomHeader("Accept", ACCEPT)
        event.userData = subscription.id
        event.addListener(eventListener)
        subscription.event = event
        if (event.sendSubscribe(null) != 0) {
            Log.e("[BLF] Couldn't send a subscription to ${subscription.id}")
        }
        startWatchdog(subscription)
    }

    private fun unsubscribe(subscription: Subscription) {
        cancelRetry(subscription)
        cancelWatchdog(subscription)
        val event = subscription.event ?: return
        subscription.event = null
        event.removeListener(eventListener)
        event.terminate()
    }

    private fun scheduleResubscribe(subscription: Subscription) {
        if (!isRegistered() || subscriptions[subscription.id] !== subscription) return
        cancelRetry(subscription)
        val delay = minOf(
            RESUBSCRIBE_DELAY_MS shl minOf(subscription.attempts, 6),
            MAX_RESUBSCRIBE_DELAY_MS
        )
        subscription.attempts++
        val retry = Runnable {
            subscription.retry = null
            if (subscriptions[subscription.id] === subscription && isRegistered()) {
                subscribe(
                    subscription
                )
            }
        }
        subscription.retry = retry
        coreContext.handler.postDelayed(retry, delay)
    }

    // Subscribes again if no NOTIFY arrives in time after subscribing
    private fun startWatchdog(subscription: Subscription) {
        cancelWatchdog(subscription)
        val watchdog = Runnable {
            subscription.watchdog = null
            Log.w("[BLF] No NOTIFY from ${subscription.id}, subscribing again")
            unsubscribe(subscription)
            scheduleResubscribe(subscription)
        }
        subscription.watchdog = watchdog
        coreContext.handler.postDelayed(watchdog, NOTIFY_TIMEOUT_MS)
    }

    private fun cancelRetry(subscription: Subscription) {
        subscription.retry?.let { coreContext.handler.removeCallbacks(it) }
        subscription.retry = null
    }

    private fun cancelWatchdog(subscription: Subscription) {
        subscription.watchdog?.let { coreContext.handler.removeCallbacks(it) }
        subscription.watchdog = null
    }

    // The event must still be the subscription's current one, not one it has replaced
    private fun subscriptionFor(event: Event): Subscription? {
        val subscription = subscriptions[event.userData as? String ?: return null] ?: return null
        return subscription.takeIf { it.event?.nativePointer == event.nativePointer }
    }

    private fun setStatus(id: String, status: BlfKeyStatus) {
        val key = keysSubject.value!![id] ?: return
        updateKey(key.copy(status = status))
    }

    private fun updateKey(key: BlfKey) {
        if (!subscriptions.containsKey(key.id)) return
        keysSubject.onNext(keysSubject.value!! + (key.id to key))
    }
}
