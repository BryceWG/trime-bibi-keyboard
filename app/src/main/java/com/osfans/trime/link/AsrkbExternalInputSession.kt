package com.osfans.trime.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class AsrkbCursorSnapshot(
    val beforeCursor: String,
    val afterCursor: String
) {
    fun bounded(maxChars: Int = MAX_CONTEXT_CHARS) = AsrkbCursorSnapshot(
        beforeCursor.takeLast(maxChars),
        afterCursor.take(maxChars)
    )

    fun text(): String = beforeCursor + afterCursor

    companion object {
        const val MAX_CONTEXT_CHARS = 1500
    }
}

internal data class AsrkbCorrectionReport(
    val sessionId: Int,
    val generation: Long,
    val snapshot: AsrkbCursorSnapshot,
    val reason: String
)

internal data class AsrkbEditorIdentity(
    val packageName: String,
    val fieldId: Int,
    val inputType: Int,
    val imeOptions: Int
)

internal class AsrkbEditorGenerationTracker {
    private var identity: AsrkbEditorIdentity? = null
    var currentGeneration: Long = 0L
        private set

    fun onStartInput(next: AsrkbEditorIdentity, restarting: Boolean): Long {
        if (!restarting || identity != next) currentGeneration++
        identity = next
        return currentGeneration
    }

    fun onFinishInput(): Long {
        identity = null
        currentGeneration++
        return currentGeneration
    }
}

internal class AsrkbNegotiationToken(
    val sessionId: Int,
    val generation: Long,
    val connection: Any?
)

internal class AsrkbNegotiationGate {
    private var current: AsrkbNegotiationToken? = null

    @Synchronized
    fun begin(sessionId: Int, generation: Long, connection: Any?): AsrkbNegotiationToken {
        return AsrkbNegotiationToken(sessionId, generation, connection).also { current = it }
    }

    @Synchronized
    fun isCurrent(token: AsrkbNegotiationToken): Boolean = current === token

    @Synchronized
    fun invalidate() {
        current = null
    }
}

internal fun launchAsrkbEditReport(
    scope: CoroutineScope,
    report: AsrkbCorrectionReport,
    submit: (AsrkbCorrectionReport) -> Unit,
    onComplete: () -> Unit = {}
): Job = scope.launch(Dispatchers.IO) {
    try {
        submit(report)
    } finally {
        onComplete()
    }
}

internal suspend inline fun negotiateOptionalInputThenContinue(
    crossinline queryRequirements: suspend () -> Int?,
    crossinline attachInputContext: suspend (Int) -> Boolean,
    crossinline continueOriginalAsr: suspend () -> Unit
): Boolean {
    var correctionEnabled = false
    try {
        val requirements = queryRequirements() ?: 0
        if (requirements != 0) {
            correctionEnabled = attachInputContext(requirements) &&
                requirements and (1 shl 1) != 0
        }
    } finally {
        continueOriginalAsr()
    }
    return correctionEnabled
}

internal class AsrkbCorrectionTracker(
    private val timeoutMs: Long = 30_000L
) {
    private data class Active(
        val sessionId: Int,
        val generation: Long,
        val startedAtMs: Long,
        val original: AsrkbCursorSnapshot,
        var lastNonEmpty: AsrkbCursorSnapshot
    )

    private var active: Active? = null

    @Synchronized
    fun start(
        sessionId: Int,
        generation: Long,
        initial: AsrkbCursorSnapshot,
        finalText: String,
        committed: AsrkbCursorSnapshot,
        nowMs: Long
    ): Boolean {
        val expected = AsrkbCursorSnapshot(
            beforeCursor = initial.beforeCursor + finalText,
            afterCursor = initial.afterCursor
        ).bounded()
        if (sessionId <= 0 || generation <= 0L || finalText.isEmpty() || committed.bounded() != expected) {
            active = null
            return false
        }
        active = Active(sessionId, generation, nowMs, expected, expected)
        return true
    }

    @Synchronized
    fun update(
        generation: Long,
        snapshot: AsrkbCursorSnapshot,
        nowMs: Long
    ): AsrkbCorrectionReport? {
        val current = active ?: return null
        if (current.generation != generation) {
            active = null
            return null
        }
        val bounded = snapshot.bounded()
        if (bounded.text().isNotEmpty()) current.lastNonEmpty = bounded
        return when {
            bounded.text().isEmpty() -> settle(current, "cleared")
            nowMs - current.startedAtMs >= timeoutMs -> settle(current, "timeout")
            else -> null
        }
    }

    @Synchronized
    fun cancel() {
        active = null
    }

    @Synchronized
    fun finish(
        generation: Long,
        snapshot: AsrkbCursorSnapshot,
        reason: String
    ): AsrkbCorrectionReport? {
        val current = active ?: return null
        if (current.generation != generation) {
            active = null
            return null
        }
        val bounded = snapshot.bounded()
        if (bounded.text().isNotEmpty()) current.lastNonEmpty = bounded
        return settle(current, reason)
    }

    @Synchronized
    fun finishLast(generation: Long, reason: String): AsrkbCorrectionReport? {
        val current = active ?: return null
        if (current.generation != generation) {
            active = null
            return null
        }
        return settle(current, reason)
    }

    @Synchronized
    fun isActive(): Boolean = active != null

    private fun settle(current: Active, reason: String): AsrkbCorrectionReport? {
        active = null
        if (current.lastNonEmpty.text() == current.original.text()) return null
        return AsrkbCorrectionReport(
            sessionId = current.sessionId,
            generation = current.generation,
            snapshot = current.lastNonEmpty,
            reason = reason
        )
    }
}

internal object AsrkbEditorPrivacy {
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    private const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x01000000

    fun isEligible(inputType: Int, imeOptions: Int): Boolean {
        if (imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return false
        val inputClass = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        if (inputClass == TYPE_CLASS_TEXT && variation in TEXT_PASSWORD_VARIATIONS) return false
        if (inputClass == TYPE_CLASS_NUMBER && variation == TYPE_NUMBER_VARIATION_PASSWORD) return false
        return inputClass != 0
    }

    private val TEXT_PASSWORD_VARIATIONS = setOf(
        TYPE_TEXT_VARIATION_PASSWORD,
        TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        TYPE_TEXT_VARIATION_WEB_PASSWORD
    )
}
