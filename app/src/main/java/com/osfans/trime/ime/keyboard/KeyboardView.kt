/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.voice.WaveformView
import com.osfans.trime.link.AsrkbSpeechClient
import com.osfans.trime.link.VoiceOverlayUiBridge
import timber.log.Timber
import androidx.core.graphics.ColorUtils as AndroidColorUtils

// TODO: move layout calculation responsibilities from Keyboard to KeyboardView using ConstraintLayout
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val theme: Theme,
    private val keyboard: Keyboard,
    val popup: PopupDelegate,
    val service: TrimeInputMethodService,
    private val keyboardActionListener: KeyboardActionListener,
    private val enterKeyDisplay: EnterKeyDisplayDelegate,
) : FrameLayout(context) {

    private val keys get() = keyboard.keys

    internal val labelEnter: String
        get() = enterKeyDisplay.keyLabel
    internal val keyTextSize = theme.generalStyle.keyTextSize
    internal val keyLongTextSize = theme.generalStyle.keyLongTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val symbolTextSize = theme.generalStyle.symbolTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val popupOnKeyPress by AppPrefs.defaultInstance().keyboard.popupOnKeyPress

    private var voiceOverlay: FrameLayout? = null
    private var voiceWave: WaveformView? = null

    init {
        setWillNotDraw(false)
        buildKeyViews()
    }

    private fun buildKeyViews() {
        removeAllViews()

        keys.forEachIndexed { index, key ->
            val keyView = createKeyView(index, key)
            addView(keyView)
        }
    }

    private fun createKeyView(index: Int, key: Key): KeyView = KeyView(
        context,
        key = key,
        keyboard = keyboard,
        keyboardView = this,
        keyboardActionListener = keyboardActionListener,
    ).apply {
        id = index

        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        layoutParams = LayoutParams(totalWidth, key.height)

        translationX = (key.x - key.extraWidthLeft).toFloat()
        translationY = key.y.toFloat()

        setPadding(
            keyboard.horizontalGap / 2 + key.extraWidthLeft,
            keyboard.verticalGap / 2,
            keyboard.horizontalGap / 2 + key.extraWidthRight,
            keyboard.verticalGap / 2,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fullWidth = keyboard.minWidth + paddingLeft + paddingRight
        val fullHeight = keyboard.height + paddingTop + paddingBottom

        val measuredWidth =
            minOf(
                MeasureSpec.getSize(widthMeasureSpec),
                fullWidth,
            )

        measureChildren(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, fullHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }

    fun invalidateAllKeys() {
        children.forEach { it.invalidate() }
    }

    fun invalidateKeyByIndex(index: Int) {
        getChildAt(index)?.invalidate()
    }

    val isCapsOn: Boolean
        get() = keyboard.mShiftKey?.isOn == true

    fun onDetach() {
        if (AsrkbSpeechClient.isHolding()) {
            hideVoiceOverlay()
            VoiceOverlayUiBridge.clear()
            AsrkbSpeechClient.stopHoldSession()
        } else {
            hideVoiceOverlay()
            VoiceOverlayUiBridge.clear()
        }
        popup.dismissAll()
    }

    internal fun showVoiceOverlay() {
        if (voiceOverlay != null) return

        val bgColor = runCatching { ColorManager.getColor("keyboard_back_color") }.getOrElse { Color.BLACK }
        val overlay =
            FrameLayout(context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

                runCatching {
                    ColorManager.getDecorDrawable("keyboard_background")?.also { background = it }
                }.onFailure { Timber.d(it, "Resolve keyboard_background drawable failed") }
                if (background == null) setBackgroundColor(bgColor)
            }

        val candidateColors =
            listOf(
                "key_text_color",
                "hilited_key_text_color",
                "candidate_text_color",
                "hilited_candidate_text_color",
            ).mapNotNull { key ->
                runCatching { ColorManager.getColor(key) }.getOrNull()
            }
        val lineColor =
            candidateColors.firstOrNull { AndroidColorUtils.calculateContrast(it, bgColor) >= 2.5 }
                ?: candidateColors.firstOrNull()
                ?: Color.WHITE

        val wave =
            WaveformView(context).apply {
                setWaveformColor(lineColor)
                visibility = View.INVISIBLE
            }
        overlay.addView(wave, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val transition =
            TransitionSet().apply {
                addTransition(Slide(Gravity.BOTTOM).apply { addTarget(overlay) })
                duration = 100
            }
        runCatching { TransitionManager.beginDelayedTransition(this, transition) }
            .onFailure { Timber.d(it, "Begin voice overlay transition failed") }

        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        voiceOverlay = overlay
        voiceWave = wave
    }

    internal fun hideVoiceOverlay() {
        val overlay = voiceOverlay ?: return
        runCatching { voiceWave?.stop() }.onFailure { Timber.w(it, "Stop WaveformView failed") }
        val transition =
            TransitionSet().apply {
                addTransition(Slide(Gravity.BOTTOM).apply { addTarget(overlay) })
                duration = 100
            }
        runCatching { TransitionManager.beginDelayedTransition(this, transition) }
            .onFailure { Timber.d(it, "Begin voice overlay transition failed") }
        runCatching { removeView(overlay) }.onFailure { Timber.w(it, "Remove voice overlay failed") }
        voiceOverlay = null
        voiceWave = null
    }

    internal fun startVoiceOverlayWave() {
        val wave = voiceWave ?: return
        wave.visibility = View.VISIBLE
        wave.start()
    }

    internal fun updateVoiceOverlayAmplitude(amplitude: Float) {
        voiceWave?.updateAmplitude(amplitude)
    }
}
