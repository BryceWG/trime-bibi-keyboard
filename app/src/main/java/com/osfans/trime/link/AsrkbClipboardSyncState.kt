/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Pure state used by the BiBi Keyboard clipboard bridge and its settings status.
 */
package com.osfans.trime.link

internal const val ASRKB_CLIPBOARD_SYNC_ENABLED_KEY = "asrkb_clipboard_sync_enabled"
internal const val ASRKB_CLIPBOARD_SYNC_STATUS_KEY = "asrkb_clipboard_sync_status"

internal enum class AsrkbClipboardSyncPhase {
    STOPPED,
    DISABLED,
    WAITING,
    CONNECTING,
    RECONNECTING,
    CONNECTED,
    OBSERVING,
    ERROR,
}

internal data class AsrkbClipboardSyncStatus(
    val phase: AsrkbClipboardSyncPhase,
    val detail: String = "",
) {
    fun encode(): String = "${phase.name}|$detail"

    companion object {
        fun decode(raw: String?): AsrkbClipboardSyncStatus {
            val (phase, detail) = raw.orEmpty().split('|', limit = 2).let {
                it.firstOrNull() to it.getOrElse(1) { "" }
            }
            return AsrkbClipboardSyncStatus(
                AsrkbClipboardSyncPhase.entries.firstOrNull { it.name == phase }
                    ?: AsrkbClipboardSyncPhase.STOPPED,
                detail,
            )
        }
    }
}

internal fun statusAfterWindowHidden(status: AsrkbClipboardSyncStatus): AsrkbClipboardSyncStatus =
    when (status.phase) {
        AsrkbClipboardSyncPhase.CONNECTING,
        AsrkbClipboardSyncPhase.RECONNECTING,
        -> AsrkbClipboardSyncStatus(AsrkbClipboardSyncPhase.WAITING)
        else -> status
    }

internal fun activeSessionPhase(isObserving: Boolean): AsrkbClipboardSyncPhase =
    if (isObserving) AsrkbClipboardSyncPhase.OBSERVING else AsrkbClipboardSyncPhase.CONNECTED

internal fun isClipboardHostRequestAuthorized(
    enabled: Boolean,
    hasSession: Boolean,
    activeHostPackage: String?,
    requestHostPackage: String,
): Boolean = enabled && hasSession && activeHostPackage == requestHostPackage

internal class AsrkbClipboardEchoSuppressor(
    private val timeoutMs: Long,
) {
    private var expectedText: String? = null
    private var expiresAtMs: Long = 0

    @Synchronized
    fun expect(text: String, nowMs: Long) {
        expectedText = text
        expiresAtMs = nowMs + timeoutMs
    }

    @Synchronized
    fun clear() {
        expectedText = null
    }

    @Synchronized
    fun shouldSuppress(text: String?, nowMs: Long): Boolean {
        val expected = expectedText ?: return false
        expectedText = null
        return nowMs <= expiresAtMs && text == expected
    }
}
