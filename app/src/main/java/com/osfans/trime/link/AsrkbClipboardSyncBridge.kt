/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Connects the IME process clipboard to BiBi Keyboard's SyncClipboard runtime.
 */
package com.osfans.trime.link

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import androidx.preference.PreferenceManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import timber.log.Timber

internal class AsrkbClipboardSyncBridge(context: Context) {
    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
    private val echoSuppressor = AsrkbClipboardEchoSuppressor(ECHO_SUPPRESSION_TIMEOUT_MS)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BiBiClipboardSyncBridge").apply { isDaemon = true }
    }
    private val receivers = HOSTS.map { host -> HostReceiver(host) }
    private var generation = 0L
    private var destroyed = false
    private var windowVisible = false
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var binder: IBinder? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var activeHost: Host? = null
    private var subscription: Subscription? = null

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ASRKB_CLIPBOARD_SYNC_ENABLED_KEY) onEnabledChanged()
        }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val current = synchronized(this) { subscription } ?: return@OnPrimaryClipChangedListener
        val snapshot = readClipboardSnapshot() ?: return@OnPrimaryClipChangedListener
        if (echoSuppressor.shouldSuppress(snapshot.text, SystemClock.elapsedRealtime())) {
            return@OnPrimaryClipChangedListener
        }
        if (!snapshot.sensitive && snapshot.text.isNullOrEmpty()) return@OnPrimaryClipChangedListener
        try {
            appContext.sendBroadcast(
                Intent(ACTION_CLIPBOARD_TEXT_CHANGED).apply {
                    setPackage(current.host.packageName)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra(EXTRA_PROTOCOL_VERSION, PROTOCOL_VERSION)
                    putExtra(EXTRA_TARGET_PACKAGE, appContext.packageName)
                    putExtra(EXTRA_CLIPBOARD_SUBSCRIPTION_TOKEN, current.token)
                    putExtra(EXTRA_CLIPBOARD_TEXT_CHARS, snapshot.text?.length ?: 0)
                    putExtra(EXTRA_IS_CLIPBOARD_SENSITIVE, snapshot.sensitive)
                    if (!snapshot.sensitive && snapshot.text != null) {
                        putExtra(EXTRA_TEXT, snapshot.text)
                    }
                },
                current.host.permission,
            )
        } catch (t: Throwable) {
            Timber.w(t, "Unable to publish clipboard change to BiBi Keyboard")
        }
    }

    fun create() {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        receivers.forEach { receiver ->
            val filter = IntentFilter().apply {
                addAction(ACTION_SET_CLIPBOARD_TEXT)
                addAction(ACTION_GET_CLIPBOARD_TEXT)
                addAction(ACTION_START_CLIPBOARD_OBSERVE)
                addAction(ACTION_STOP_CLIPBOARD_OBSERVE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    receiver,
                    filter,
                    receiver.host.permission,
                    null,
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter, receiver.host.permission, null)
            }
        }
        updateStatus(
            if (isEnabled()) AsrkbClipboardSyncPhase.WAITING else AsrkbClipboardSyncPhase.DISABLED,
        )
    }

    @Synchronized
    fun windowShown() {
        if (destroyed) return
        windowVisible = true
        if (!isEnabled()) {
            updateStatus(AsrkbClipboardSyncPhase.DISABLED)
            return
        }
        val currentGeneration = ++generation
        updateStatus(AsrkbClipboardSyncPhase.CONNECTING)
        executor.execute { activate(currentGeneration) }
    }

    @Synchronized
    fun windowHidden() {
        if (destroyed) return
        windowVisible = false
        generation++
        executor.execute { transactSession(TRANSACTION_WINDOW_HIDDEN) }
        if (sessionId == null && isEnabled()) {
            val current = AsrkbClipboardSyncStatus.decode(
                preferences.getString(ASRKB_CLIPBOARD_SYNC_STATUS_KEY, null),
            )
            val next = statusAfterWindowHidden(current)
            updateStatus(next.phase, next.detail)
        }
    }

    @Synchronized
    fun destroy() {
        if (destroyed) return
        destroyed = true
        windowVisible = false
        generation++
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        receivers.forEach {
            try {
                appContext.unregisterReceiver(it)
            } catch (t: Throwable) {
                Timber.w(t, "Unable to unregister BiBi clipboard receiver")
            }
        }
        stopObserving()
        executor.execute {
            transactSession(TRANSACTION_DEACTIVATE)
            unbind()
        }
        executor.shutdown()
        updateStatus(AsrkbClipboardSyncPhase.STOPPED)
    }

    @Synchronized
    private fun onEnabledChanged() {
        if (destroyed) return
        generation++
        if (!isEnabled()) {
            stopObserving()
            updateStatus(AsrkbClipboardSyncPhase.DISABLED)
            executor.execute {
                transactSession(TRANSACTION_DEACTIVATE)
                unbind()
            }
            return
        }
        if (!windowVisible) {
            updateStatus(AsrkbClipboardSyncPhase.WAITING)
            return
        }
        val currentGeneration = generation
        updateStatus(AsrkbClipboardSyncPhase.CONNECTING)
        executor.execute { activate(currentGeneration) }
    }

    private fun activate(candidateGeneration: Long) {
        if (!isCurrent(candidateGeneration)) return
        if (binder?.isBinderAlive == true && sessionId != null) {
            val (phase, hostName) = synchronized(this) {
                activeSessionPhase(subscription?.host == activeHost) to activeHost?.name.orEmpty()
            }
            updateStatus(phase, hostName)
            return
        }
        unbind()
        val newSessionId = UUID.randomUUID().toString()
        val attempts = mutableListOf<String>()
        for (host in HOSTS) {
            if (!isCurrent(candidateGeneration)) break
            if (!bind(host)) {
                attempts += "${host.name}=0"
                unbind()
                continue
            }
            if (!isCurrent(candidateGeneration)) {
                unbind()
                break
            }
            val result = activate(newSessionId)
            attempts += "${host.name}=$result"
            if (result == RESULT_OK && isCurrent(candidateGeneration)) {
                synchronized(this) {
                    sessionId = newSessionId
                    activeHost = host
                }
                updateStatus(AsrkbClipboardSyncPhase.CONNECTED, host.name)
                return
            }
            unbind()
        }
        if (isCurrent(candidateGeneration)) {
            updateStatus(AsrkbClipboardSyncPhase.ERROR, attempts.joinToString(", "))
        }
    }

    private fun bind(host: Host): Boolean {
        val latch = CountDownLatch(1)
        var connected = false
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (connection !== this) return
                binder = service
                connected = service != null
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                reconnectAfterLoss(this)
            }

            override fun onBindingDied(name: ComponentName?) {
                reconnectAfterLoss(this)
            }
        }
        connection = newConnection
        return try {
            val intent = Intent(ACTION_BIND_CLIPBOARD_SYNC).apply {
                component = ComponentName(host.packageName, CLIPBOARD_SYNC_SERVICE)
            }
            if (!appContext.bindService(intent, newConnection, Context.BIND_AUTO_CREATE)) {
                connection = null
                false
            } else {
                latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                connected && binder?.isBinderAlive == true
            }
        } catch (t: Throwable) {
            Timber.w(t, "Unable to bind BiBi clipboard sync host %s", host.packageName)
            unbind()
            false
        }
    }

    private fun activate(newSessionId: String): Int = transact(TRANSACTION_ACTIVATE) { data ->
        data.writeInt(NATIVE_ACTIVATION_PROTOCOL_VERSION)
        data.writeString(newSessionId)
        data.writeString(appContext.packageName)
    }

    @Synchronized
    private fun reconnectAfterLoss(expectedConnection: ServiceConnection) {
        if (connection !== expectedConnection || destroyed) return
        try {
            appContext.unbindService(expectedConnection)
        } catch (t: Throwable) {
            Timber.w(t, "Unable to release disconnected BiBi clipboard sync host")
        }
        connection = null
        binder = null
        sessionId = null
        activeHost = null
        stopObserving()
        if (!windowVisible || !isEnabled()) {
            updateStatus(
                if (isEnabled()) AsrkbClipboardSyncPhase.WAITING else AsrkbClipboardSyncPhase.DISABLED,
            )
            return
        }
        val currentGeneration = ++generation
        updateStatus(AsrkbClipboardSyncPhase.RECONNECTING)
        executor.execute { activate(currentGeneration) }
    }

    private fun transactSession(code: Int) {
        val currentSession = synchronized(this) { sessionId } ?: return
        transact(code) { it.writeString(currentSession) }
    }

    private fun transact(code: Int, write: (Parcel) -> Unit): Int {
        val currentBinder = binder ?: return RESULT_UNAVAILABLE
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CLIPBOARD_SYNC_SERVICE)
            write(data)
            currentBinder.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (t: Throwable) {
            Timber.w(t, "BiBi clipboard sync transaction failed")
            RESULT_UNAVAILABLE
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun unbind() {
        connection?.let {
            try {
                appContext.unbindService(it)
            } catch (t: Throwable) {
                Timber.w(t, "Unable to unbind BiBi clipboard sync host")
            }
        }
        connection = null
        binder = null
        synchronized(this) {
            sessionId = null
            activeHost = null
        }
    }

    @Synchronized
    private fun isCurrent(candidateGeneration: Long): Boolean =
        !destroyed && generation == candidateGeneration

    @Synchronized
    private fun startObserving(host: Host, token: String): Boolean {
        if (token.isEmpty()) return false
        val current = subscription
        if (current != null && current.host != host) return false
        subscription = Subscription(host, token)
        if (current == null) {
            try {
                clipboard?.addPrimaryClipChangedListener(clipboardListener)
            } catch (t: Throwable) {
                Timber.w(t, "Unable to observe system clipboard")
                subscription = null
                return false
            }
        }
        return clipboard != null
    }

    @Synchronized
    private fun stopObserving(host: Host? = null) {
        val current = subscription ?: return
        if (host != null && current.host != host) return
        subscription = null
        try {
            clipboard?.removePrimaryClipChangedListener(clipboardListener)
        } catch (t: Throwable) {
            Timber.w(t, "Unable to stop observing system clipboard")
        }
    }

    private fun writeClipboardText(text: CharSequence): Boolean {
        if (text.isEmpty() || clipboard == null) return false
        echoSuppressor.expect(text.toString(), SystemClock.elapsedRealtime())
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("SyncClipboard", text))
            true
        } catch (t: Throwable) {
            echoSuppressor.clear()
            Timber.w(t, "Unable to write system clipboard")
            false
        }
    }

    private fun readClipboardSnapshot(): ClipboardSnapshot? {
        val clip = try {
            clipboard?.primaryClip
        } catch (t: Throwable) {
            Timber.w(t, "Unable to read system clipboard")
            null
        } ?: return null
        if (clip.itemCount <= 0) return null
        val text = try {
            clip.getItemAt(0).text?.toString()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Timber.w(t, "Unable to read clipboard text")
            null
        }
        val sensitive = try {
            val extras = clip.description.extras
            when {
                extras == null -> false
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)
                else -> extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
            }
        } catch (t: Throwable) {
            Timber.w(t, "Unable to read clipboard sensitivity; treating it as sensitive")
            true
        }
        return ClipboardSnapshot(text, sensitive)
    }

    private inner class HostReceiver(val host: Host) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra(EXTRA_PROTOCOL_VERSION, 0) != PROTOCOL_VERSION) {
                finish(RESULT_PROTOCOL_MISMATCH, "protocol mismatch")
                return
            }
            if (!isHostAuthorized(host)) {
                finish(RESULT_UNAVAILABLE, "inactive sync session")
                return
            }
            when (intent.action) {
                ACTION_SET_CLIPBOARD_TEXT -> {
                    val text = intent.getCharSequenceExtra(EXTRA_TEXT)
                    when {
                        text.isNullOrEmpty() -> finish(RESULT_BAD_REQUEST, "empty text")
                        writeClipboardText(text) -> finish(RESULT_OK, "ok")
                        else -> finish(RESULT_CLIPBOARD_FAILED, "clipboard write failed")
                    }
                }
                ACTION_GET_CLIPBOARD_TEXT -> {
                    val snapshot = readClipboardSnapshot()
                    if (snapshot?.text == null) {
                        finish(RESULT_CLIPBOARD_FAILED, "empty clipboard")
                    } else {
                        setResultExtras((getResultExtras(true) ?: Bundle()).apply {
                            putString(EXTRA_TEXT, snapshot.text)
                            putInt(EXTRA_CLIPBOARD_TEXT_CHARS, snapshot.text.length)
                            putBoolean(EXTRA_IS_CLIPBOARD_SENSITIVE, snapshot.sensitive)
                        })
                        finish(RESULT_OK, "ok")
                    }
                }
                ACTION_START_CLIPBOARD_OBSERVE -> {
                    val token = intent.getStringExtra(EXTRA_CLIPBOARD_SUBSCRIPTION_TOKEN).orEmpty()
                    val observing = startObserving(host, token)
                    if (observing) updateStatus(AsrkbClipboardSyncPhase.OBSERVING, host.name)
                    finish(if (observing) RESULT_OK else RESULT_CLIPBOARD_FAILED, if (observing) "observing" else "observe failed")
                }
                ACTION_STOP_CLIPBOARD_OBSERVE -> {
                    stopObserving(host)
                    activeHost?.let { updateStatus(AsrkbClipboardSyncPhase.CONNECTED, it.name) }
                    finish(RESULT_OK, "stopped")
                }
                else -> finish(RESULT_BAD_REQUEST, "unknown action")
            }
        }

        private fun finish(code: Int, message: String) {
            setResultExtras((getResultExtras(true) ?: Bundle()).apply {
                putString(EXTRA_TARGET_PACKAGE, appContext.packageName)
                putBoolean(EXTRA_SUPPORTS_CLIPBOARD, true)
                putString(EXTRA_MESSAGE, message)
            })
            resultCode = code
            resultData = message
        }
    }

    private fun isEnabled(): Boolean =
        preferences.getBoolean(ASRKB_CLIPBOARD_SYNC_ENABLED_KEY, false)

    @Synchronized
    private fun isHostAuthorized(host: Host): Boolean = isClipboardHostRequestAuthorized(
        enabled = isEnabled(),
        hasSession = sessionId != null,
        activeHostPackage = activeHost?.packageName,
        requestHostPackage = host.packageName,
    )

    private fun updateStatus(phase: AsrkbClipboardSyncPhase, detail: String = "") {
        val value = AsrkbClipboardSyncStatus(phase, detail).encode()
        if (preferences.getString(ASRKB_CLIPBOARD_SYNC_STATUS_KEY, null) == value) return
        preferences.edit().putString(ASRKB_CLIPBOARD_SYNC_STATUS_KEY, value).apply()
    }

    private data class ClipboardSnapshot(val text: String?, val sensitive: Boolean)
    private data class Host(val name: String, val packageName: String, val permission: String)
    private data class Subscription(val host: Host, val token: String)

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val NATIVE_ACTIVATION_PROTOCOL_VERSION = 2
        const val BIND_TIMEOUT_MS = 700L
        const val ECHO_SUPPRESSION_TIMEOUT_MS = 5_000L
        const val RESULT_OK = 1
        const val RESULT_PROTOCOL_MISMATCH = -2
        const val RESULT_BAD_REQUEST = -7
        const val RESULT_CLIPBOARD_FAILED = -10
        const val RESULT_UNAVAILABLE = 0
        const val TRANSACTION_ACTIVATE = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_DEACTIVATE = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_WINDOW_HIDDEN = IBinder.FIRST_CALL_TRANSACTION + 2
        const val CLIPBOARD_SYNC_SERVICE = "com.brycewg.asrkb.imebridge.ImeBridgeClipboardSyncService"
        const val ACTION_BIND_CLIPBOARD_SYNC = "com.brycewg.asrkb.imebridge.action.BIND_CLIPBOARD_SYNC_RUNTIME"
        const val ACTION_SET_CLIPBOARD_TEXT = "com.brycewg.asrkb.imebridge.action.SET_CLIPBOARD_TEXT"
        const val ACTION_GET_CLIPBOARD_TEXT = "com.brycewg.asrkb.imebridge.action.GET_CLIPBOARD_TEXT"
        const val ACTION_START_CLIPBOARD_OBSERVE = "com.brycewg.asrkb.imebridge.action.START_CLIPBOARD_OBSERVE"
        const val ACTION_STOP_CLIPBOARD_OBSERVE = "com.brycewg.asrkb.imebridge.action.STOP_CLIPBOARD_OBSERVE"
        const val ACTION_CLIPBOARD_TEXT_CHANGED = "com.brycewg.asrkb.imebridge.action.CLIPBOARD_TEXT_CHANGED"
        const val EXTRA_PROTOCOL_VERSION = "protocol_version"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_SUPPORTS_CLIPBOARD = "supports_clipboard"
        const val EXTRA_IS_CLIPBOARD_SENSITIVE = "is_clipboard_sensitive"
        const val EXTRA_CLIPBOARD_TEXT_CHARS = "clipboard_text_chars"
        const val EXTRA_CLIPBOARD_SUBSCRIPTION_TOKEN = "clipboard_subscription_token"
        const val EXTRA_MESSAGE = "message"

        val HOSTS = listOf(
            Host("Pro", "com.brycewg.asrkb.pro", "com.brycewg.asrkb.pro.permission.IME_BRIDGE"),
            Host("OSS", "com.brycewg.asrkb", "com.brycewg.asrkb.permission.IME_BRIDGE"),
        )
    }
}
