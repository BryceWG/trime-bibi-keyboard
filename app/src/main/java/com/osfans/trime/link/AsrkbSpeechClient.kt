/*
 * Minimal external AIDL client to link with BiBi Keyboard (asr-keyboard)
 * via raw Binder transact calls (push PCM mode).
 */
package com.osfans.trime.link

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.max

object AsrkbSpeechClient {
    @Volatile
    var onHoldingChanged: ((Boolean) -> Unit)? = null

    private var bound = false
    private var connection: ServiceConnection? = null
    private var remote: IBinder? = null
    private var callbackBinder: IBinder? = null
    private var sessionId: Int = -1
    private var currentState: Int = STATE_IDLE
    @Volatile
    private var holding: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            val listener = onHoldingChanged ?: return
            runCatching { listener.invoke(value) }
                .onFailure { Timber.w(it, "onHoldingChanged failed") }
        }
    private var ctxRef: TrimeInputMethodService? = null
    private var audioJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var recordingAudioFocusController: AsrkbRecordingAudioFocusController? = null
    private var hasPcmFrame: Boolean = false

    fun startHoldSession(service: TrimeInputMethodService) {
        if (bound && remote != null && sessionId > 0) {
            if (!holding) {
                Timber.w("Reset stale session before starting new hold (state=$currentState)")
                unbind(clearUi = false)
            } else {
                return
            }
        }

        ctxRef = service
        holding = true
        hasPcmFrame = false

        val conn =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val ctx = ctxRef ?: return
                    if (!holding) {
                        Timber.w("Service connected but session is no longer holding; ignore")
                        unbind()
                        return
                    }
                    try {
                        val b = binder ?: throw IllegalStateException("no binder")
                        remote = b

                        val cbBinder =
                            object : Binder() {
                                override fun onTransact(
                                    code: Int,
                                    data: Parcel,
                                    reply: Parcel?,
                                    flags: Int,
                                ): Boolean {
                                    return try {
                                        when (code) {
                                            CB_onState -> {
                                                data.enforceInterface(DESCRIPTOR_CB)
                                                data.readInt()
                                                val s = data.readInt()
                                                data.readString()
                                                currentState = s
                                                reply?.writeNoException()
                                                true
                                            }
                                            CB_onPartial -> {
                                                data.enforceInterface(DESCRIPTOR_CB)
                                                data.readInt()
                                                val text = data.readString() ?: ""
                                                ctx.lifecycleScope.launch {
                                                    ctx.currentInputConnection?.setComposingText(text, 1)
                                                }
                                                reply?.writeNoException()
                                                true
                                            }
                                            CB_onFinal -> {
                                                data.enforceInterface(DESCRIPTOR_CB)
                                                data.readInt()
                                                val text = data.readString() ?: ""
                                                ctx.lifecycleScope.launch {
                                                    ctx.commitText(text)
                                                    unbind()
                                                }
                                                reply?.writeNoException()
                                                true
                                            }
                                            CB_onError -> {
                                                data.enforceInterface(DESCRIPTOR_CB)
                                                data.readInt()
                                                val codeVal = data.readInt()
                                                val msg = data.readString()
                                                toast(ctx, mapCallbackError(ctx, codeVal, msg))
                                                unbind()
                                                reply?.writeNoException()
                                                true
                                            }
                                            CB_onAmplitude -> {
                                                data.enforceInterface(DESCRIPTOR_CB)
                                                data.readInt()
                                                val amp = data.readFloat()
                                                runCatching { VoiceOverlayUiBridge.onAmplitude?.invoke(amp) }
                                                reply?.writeNoException()
                                                true
                                            }
                                            IBinder.INTERFACE_TRANSACTION -> {
                                                reply?.writeString(DESCRIPTOR_CB)
                                                true
                                            }
                                            else -> super.onTransact(code, data, reply, flags)
                                        }
                                    } catch (t: Throwable) {
                                        Timber.w(t, "callback transact handle failed (code=$code)")
                                        false
                                    }
                                }
                            }
                        callbackBinder = cbBinder

                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        var sid = -999
                        try {
                            data.writeInterfaceToken(DESCRIPTOR_SVC)
                            // push PCM mode: presence=0 means no SpeechConfig; server follows its current settings
                            data.writeInt(0)
                            data.writeStrongBinder(cbBinder)
                            b.transact(TRANSACTION_startPcmSession, data, reply, 0)
                            reply.readException()
                            sid = reply.readInt()
                        } finally {
                            try {
                                data.recycle()
                            } catch (t: Throwable) {
                                Timber.w(t, "data.recycle failed")
                            }
                            try {
                                reply.recycle()
                            } catch (t: Throwable) {
                                Timber.w(t, "reply.recycle failed")
                            }
                        }

                        if (sid <= 0) {
                            toast(ctx, mapStartError(ctx, sid))
                            unbind()
                        } else {
                            sessionId = sid
                            currentState = STATE_RECORDING
                            startAudioStreaming(ctx)
                        }
                    } catch (t: Throwable) {
                        Timber.w(t, "bind/start failed")
                        toast(ctx, ctx.getString(R.string.asrkb_err_connect_failed))
                        unbind()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    unbind()
                }
            }

        connection = conn
        val candidates =
            listOf(
                ComponentName("com.brycewg.asrkb.pro", "com.brycewg.asrkb.api.ExternalSpeechService"),
                ComponentName("com.brycewg.asrkb", "com.brycewg.asrkb.api.ExternalSpeechService"),
            )
        for (c in candidates) {
            val intent = Intent().apply { component = c }
            try {
                bound = service.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                if (bound) break
            } catch (t: Throwable) {
                Timber.d(t, "bind attempt failed: ${c.packageName}")
            }
        }
        if (!bound) {
            toast(service, service.getString(R.string.asrkb_err_service_not_found))
            unbind()
        }
    }

    fun stopHoldSession() {
        if (!holding) return
        holding = false

        when (currentState) {
            STATE_RECORDING, STATE_PROCESSING -> if (hasPcmFrame) finishPcmSession() else cancelAndUnbind()
            else -> cancelAndUnbind()
        }
    }

    fun isHolding(): Boolean = holding

    private fun cancelAndUnbind() {
        cancelSession()
        unbind()
    }

    private fun unbind(clearUi: Boolean = true) {
        if (clearUi) {
            runCatching { VoiceOverlayUiBridge.onDone?.invoke() }
            VoiceOverlayUiBridge.clear()
        }
        callbackBinder = null
        val ctx = ctxRef
        stopAudioStreaming()

        val conn = connection
        if (bound && conn != null && ctx != null) {
            try {
                ctx.unbindService(conn)
            } catch (t: Throwable) {
                Timber.w(t, "unbindService failed")
            }
        }

        bound = false
        connection = null
        remote = null
        sessionId = -1
        currentState = STATE_IDLE
        holding = false
        ctxRef = null
        hasPcmFrame = false
    }

    private fun finishPcmSession() {
        val b = remote ?: return
        val sid = sessionId
        if (sid <= 0) return

        stopAudioStreaming()

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_SVC)
            data.writeInt(sid)
            b.transact(TRANSACTION_finishPcm, data, reply, 0)
            reply.readException()
        } catch (t: Throwable) {
            Timber.w(t, "finishPcmSession failed")
            cancelAndUnbind()
        } finally {
            try {
                data.recycle()
            } catch (t: Throwable) {
                Timber.w(t, "data.recycle failed")
            }
            try {
                reply.recycle()
            } catch (t: Throwable) {
                Timber.w(t, "reply.recycle failed")
            }
        }
    }

    private fun cancelSession() {
        val b = remote ?: return
        val sid = sessionId
        if (sid <= 0) return

        stopAudioStreaming()

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_SVC)
            data.writeInt(sid)
            b.transact(TRANSACTION_cancelSession, data, reply, 0)
            reply.readException()
        } catch (t: Throwable) {
            Timber.w(t, "cancelSession failed")
        } finally {
            try {
                data.recycle()
            } catch (t: Throwable) {
                Timber.w(t, "data.recycle failed")
            }
            try {
                reply.recycle()
            } catch (t: Throwable) {
                Timber.w(t, "reply.recycle failed")
            }
        }
    }

    private fun startAudioStreaming(service: TrimeInputMethodService) {
        stopAudioStreaming()

        val permGranted =
            ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!permGranted) {
            val intent =
                Intent(service, MicPermissionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            try {
                service.startActivity(intent)
            } catch (t: Throwable) {
                Timber.w(t, "Failed to start MicPermissionActivity")
                toast(service, service.getString(R.string.asrkb_client_need_mic_permission))
            }
            unbind()
            return
        }

        acquireRecordingAudioFocusIfEnabled(service)

        audioJob =
            service.lifecycleScope.launch(Dispatchers.IO) {
                val sr = 16000
                val ch = AudioFormat.CHANNEL_IN_MONO
                val fmt = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioRecord.getMinBufferSize(sr, ch, fmt)
                val bytesPerSample = 2
                val chunkBytes = (sr * 200 / 1000) * bytesPerSample
                val bufSize = max(minBuf, chunkBytes * 2)

                fun createAudioRecord(source: Int): AudioRecord? {
                    val rec =
                        try {
                            AudioRecord(
                                source,
                                sr,
                                ch,
                                fmt,
                                bufSize,
                            )
                        } catch (t: Throwable) {
                            Timber.w(t, "AudioRecord construct failed (source=$source)")
                            null
                        }
                    if (rec == null) return null
                    if (rec.state != AudioRecord.STATE_INITIALIZED) {
                        Timber.w("AudioRecord not initialized (source=$source, state=${rec.state})")
                        runCatching { rec.release() }
                        return null
                    }
                    return rec
                }

                fun toastAndUnbind() {
                    service.lifecycleScope.launch {
                        toast(service, service.getString(R.string.asrkb_err_audio_record_failed))
                        unbind()
                    }
                }

                var usedSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
                var rec = createAudioRecord(usedSource) ?: run {
                    usedSource = MediaRecorder.AudioSource.MIC
                    createAudioRecord(usedSource)
                }
                if (rec == null) {
                    toastAndUnbind()
                    return@launch
                }

                audioRecord = rec
                try {
                    rec.startRecording()
                } catch (t: Throwable) {
                    if (usedSource == MediaRecorder.AudioSource.MIC) {
                        Timber.e(t, "AudioRecord MIC failed")
                        toastAndUnbind()
                        return@launch
                    }

                    Timber.w(t, "AudioRecord start failed, fallback MIC")
                    runCatching { rec.release() }.onFailure { e ->
                        Timber.w(e, "AudioRecord release failed")
                    }

                    usedSource = MediaRecorder.AudioSource.MIC
                    val micRec = createAudioRecord(usedSource)
                    if (micRec == null) {
                        toastAndUnbind()
                        return@launch
                    }
                    audioRecord = micRec
                    rec = micRec
                    try {
                        rec.startRecording()
                    } catch (e: Throwable) {
                        Timber.e(e, "AudioRecord MIC failed")
                        toastAndUnbind()
                        return@launch
                    }
                }

                val chunk = ByteArray(chunkBytes)
                var notifiedRecordingStarted = false
                while (true) {
                    if (sessionId <= 0 || remote == null) break
                    val n =
                        try {
                            audioRecord?.read(chunk, 0, chunk.size) ?: -1
                        } catch (_: Throwable) {
                            -1
                        }
                    if (n < 0) break
                    if (n == 0) {
                        delay(10)
                        continue
                    }
                    if (!notifiedRecordingStarted) {
                        notifiedRecordingStarted = true
                        runCatching { VoiceOverlayUiBridge.onRecordingStarted?.invoke() }
                    }
                    writePcmFrame(chunk, n, sr, 1)
                }
            }
    }

    private fun stopAudioStreaming() {
        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Timber.w(t, "audioJob.cancel failed")
        } finally {
            audioJob = null
        }

        val rec = audioRecord
        if (rec != null) {
            try {
                rec.stop()
            } catch (t: Throwable) {
                Timber.w(t, "AudioRecord stop failed")
            }
            try {
                rec.release()
            } catch (t: Throwable) {
                Timber.w(t, "AudioRecord release failed")
            }
            audioRecord = null
        }

        val focusController = recordingAudioFocusController
        recordingAudioFocusController = null
        focusController?.release()
    }

    private fun acquireRecordingAudioFocusIfEnabled(service: TrimeInputMethodService) {
        val enabled =
            AppPrefs.defaultInstance().general.asrkbDuckMediaOnRecordEnabled.getValue()
        if (!enabled) {
            Timber.d("ASRKB media avoidance disabled; skip audio focus request")
            return
        }

        val executor = ContextCompat.getMainExecutor(service)
        val controller =
            AsrkbRecordingAudioFocusController(service) { loss ->
                Timber.w("ASRKB recording audio focus lost: $loss")
                executor.execute {
                    if (holding) stopHoldSession()
                }
            }
        recordingAudioFocusController = controller
        if (!controller.acquire()) {
            recordingAudioFocusController = null
            Timber.w("ASRKB recording continues without audio focus")
        }
    }

    private fun writePcmFrame(buf: ByteArray, len: Int, sr: Int, ch: Int) {
        val b = remote ?: return
        val sid = sessionId
        if (sid <= 0) return
        if (len > 0) hasPcmFrame = true

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_SVC)
            data.writeInt(sid)
            if (len == buf.size) {
                data.writeByteArray(buf)
            } else {
                data.writeByteArray(buf.copyOf(len))
            }
            data.writeInt(sr)
            data.writeInt(ch)
            b.transact(TRANSACTION_writePcm, data, reply, 0)
            reply.readException()
        } catch (t: Throwable) {
            Timber.w(t, "writePcm transact failed")
        } finally {
            try {
                data.recycle()
            } catch (t: Throwable) {
                Timber.w(t, "data.recycle failed")
            }
            try {
                reply.recycle()
            } catch (t: Throwable) {
                Timber.w(t, "reply.recycle failed")
            }
        }
    }

    private fun toast(ctx: Context, msg: String) {
        try {
            ContextCompat.getMainExecutor(ctx).execute {
                ctx.toast(msg)
            }
        } catch (t: Throwable) {
            Timber.w(t, "Toast failed")
        }
    }

    private fun mapStartError(ctx: Context, code: Int): String {
        return when (code) {
            -2 -> ctx.getString(R.string.asrkb_err_busy)
            -3 -> ctx.getString(R.string.asrkb_err_feature_disabled)
            -5 -> ctx.getString(R.string.asrkb_err_unsupported)
            else -> ctx.getString(R.string.asrkb_err_start_failed_with_code, code)
        }
    }

    private fun mapCallbackError(ctx: Context, code: Int, msg: String?): String {
        return when (code) {
            403 -> ctx.getString(R.string.asrkb_err_feature_disabled)
            else -> ctx.getString(R.string.asrkb_err_service_error_with_code, code)
        }.let { base ->
            if (msg.isNullOrBlank()) base else "$base: $msg"
        }
    }

    private const val DESCRIPTOR_SVC = "com.brycewg.asrkb.aidl.IExternalSpeechService"
    private const val TRANSACTION_cancelSession = IBinder.FIRST_CALL_TRANSACTION + 2
    private const val TRANSACTION_startPcmSession = IBinder.FIRST_CALL_TRANSACTION + 6
    private const val TRANSACTION_writePcm = IBinder.FIRST_CALL_TRANSACTION + 7
    private const val TRANSACTION_finishPcm = IBinder.FIRST_CALL_TRANSACTION + 8

    private const val DESCRIPTOR_CB = "com.brycewg.asrkb.aidl.ISpeechCallback"
    private const val CB_onState = IBinder.FIRST_CALL_TRANSACTION + 0
    private const val CB_onPartial = IBinder.FIRST_CALL_TRANSACTION + 1
    private const val CB_onFinal = IBinder.FIRST_CALL_TRANSACTION + 2
    private const val CB_onError = IBinder.FIRST_CALL_TRANSACTION + 3
    private const val CB_onAmplitude = IBinder.FIRST_CALL_TRANSACTION + 4

    private const val STATE_IDLE = 0
    private const val STATE_RECORDING = 1
    private const val STATE_PROCESSING = 2
    private const val STATE_ERROR = 3
}
