/*
 * SPDX-FileCopyrightText: 2026 BryceWG
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.link

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber

/** Controls the temporary audio-focus lease owned by an ASRKB microphone session. */
internal enum class AsrkbRecordingAudioFocusLoss {
    TRANSIENT,
    MAY_DUCK,
    PERMANENT,
}

internal interface AsrkbRecordingAudioFocusHandle

internal interface AsrkbRecordingAudioFocusGateway {
    fun requestFocus(onFocusChange: (Int) -> Unit): AsrkbRecordingAudioFocusHandle?

    fun abandonFocus(handle: AsrkbRecordingAudioFocusHandle)
}

internal fun asrkbRecordingAudioFocusLossFromChange(change: Int): AsrkbRecordingAudioFocusLoss? = when (change) {
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AsrkbRecordingAudioFocusLoss.TRANSIENT
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AsrkbRecordingAudioFocusLoss.MAY_DUCK
    AudioManager.AUDIOFOCUS_LOSS -> AsrkbRecordingAudioFocusLoss.PERMANENT
    else -> null
}

internal class AsrkbRecordingAudioFocusController internal constructor(
    private val gateway: AsrkbRecordingAudioFocusGateway,
    private val onFocusLost: (AsrkbRecordingAudioFocusLoss) -> Unit,
) {
    constructor(
        context: Context,
        onFocusLost: (AsrkbRecordingAudioFocusLoss) -> Unit,
    ) : this(
        gateway = AndroidAsrkbRecordingAudioFocusGateway(context.applicationContext),
        onFocusLost = onFocusLost,
    )

    private val lock = Any()
    private var requestGeneration = 0L
    private var activeHandle: AsrkbRecordingAudioFocusHandle? = null

    fun acquire(): Boolean {
        release()
        val generation = synchronized(lock) {
            requestGeneration += 1L
            requestGeneration
        }
        val handle =
            try {
                gateway.requestFocus { change -> onPlatformFocusChange(generation, change) }
            } catch (t: Throwable) {
                Timber.w(t, "Failed to request ASRKB recording audio focus")
                null
            } ?: return false

        val retained = synchronized(lock) {
            if (requestGeneration == generation && activeHandle == null) {
                activeHandle = handle
                true
            } else {
                false
            }
        }
        if (!retained) abandonSafely(handle)
        return retained
    }

    fun release() {
        val handle = synchronized(lock) {
            requestGeneration += 1L
            val current = activeHandle
            activeHandle = null
            current
        } ?: return
        abandonSafely(handle)
    }

    internal fun isHeldForTest(): Boolean = synchronized(lock) { activeHandle != null }

    private fun onPlatformFocusChange(
        generation: Long,
        change: Int,
    ) {
        val loss = asrkbRecordingAudioFocusLossFromChange(change) ?: return
        val handle = synchronized(lock) {
            if (requestGeneration != generation) return
            val current = activeHandle ?: return
            activeHandle = null
            requestGeneration += 1L
            current
        }
        abandonSafely(handle)
        try {
            onFocusLost(loss)
        } catch (t: Throwable) {
            Timber.w(t, "ASRKB recording audio focus loss callback failed")
        }
    }

    private fun abandonSafely(handle: AsrkbRecordingAudioFocusHandle) {
        try {
            gateway.abandonFocus(handle)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to abandon ASRKB recording audio focus")
        }
    }
}

/** Serializes the audio-focus lease ownership of the current ASRKB session. */
internal class AsrkbRecordingAudioFocusSessionOwner {
    private val lock = Any()
    private var activeController: AsrkbRecordingAudioFocusController? = null

    fun acquire(controller: AsrkbRecordingAudioFocusController): Boolean = synchronized(lock) {
        if (activeController != null || !controller.acquire()) return@synchronized false
        activeController = controller
        true
    }

    fun release() {
        val controller = synchronized(lock) {
            val current = activeController
            activeController = null
            current
        }
        controller?.release()
    }

    fun owns(controller: AsrkbRecordingAudioFocusController): Boolean = synchronized(lock) {
        activeController === controller
    }
}

private class AndroidAsrkbRecordingAudioFocusGateway(
    context: Context,
) : AsrkbRecordingAudioFocusGateway {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun requestFocus(onFocusChange: (Int) -> Unit): AsrkbRecordingAudioFocusHandle? {
        val manager = audioManager ?: run {
            Timber.w("AudioManager is unavailable for ASRKB recording")
            return null
        }
        val listener = AudioManager.OnAudioFocusChangeListener(onFocusChange)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestModernFocus(manager, listener)
        } else {
            requestLegacyFocus(manager, listener)
        }
    }

    override fun abandonFocus(handle: AsrkbRecordingAudioFocusHandle) {
        (handle as? AndroidFocusHandle)?.abandon?.invoke()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestModernFocus(
        manager: AudioManager,
        listener: AudioManager.OnAudioFocusChangeListener,
    ): AsrkbRecordingAudioFocusHandle? {
        val attributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener)
                .build()
        val result = manager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.w("ASRKB recording audio focus was not granted: result=$result")
            return null
        }
        return AndroidFocusHandle {
            val abandonResult = manager.abandonAudioFocusRequest(request)
            if (abandonResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Timber.w("ASRKB recording audio focus abandon returned result=$abandonResult")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestLegacyFocus(
        manager: AudioManager,
        listener: AudioManager.OnAudioFocusChangeListener,
    ): AsrkbRecordingAudioFocusHandle? {
        val result =
            manager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Timber.w("Legacy ASRKB recording audio focus was not granted: result=$result")
            return null
        }
        return AndroidFocusHandle {
            val abandonResult = manager.abandonAudioFocus(listener)
            if (abandonResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Timber.w("Legacy ASRKB audio focus abandon returned result=$abandonResult")
            }
        }
    }

    private class AndroidFocusHandle(
        val abandon: () -> Unit,
    ) : AsrkbRecordingAudioFocusHandle
}
