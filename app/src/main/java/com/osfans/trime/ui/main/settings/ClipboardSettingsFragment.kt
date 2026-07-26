/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.link.ASRKB_CLIPBOARD_SYNC_ENABLED_KEY
import com.osfans.trime.link.ASRKB_CLIPBOARD_SYNC_STATUS_KEY
import com.osfans.trime.link.AsrkbClipboardSyncPhase
import com.osfans.trime.link.AsrkbClipboardSyncStatus

class ClipboardSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().clipboard) {
    private lateinit var statusPreference: Preference
    private val statusListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ASRKB_CLIPBOARD_SYNC_ENABLED_KEY) showToggleNotice()
            if (key == ASRKB_CLIPBOARD_SYNC_ENABLED_KEY || key == ASRKB_CLIPBOARD_SYNC_STATUS_KEY) {
                updateStatus()
            }
        }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        statusPreference = Preference(screen.context).apply {
            key = "asrkb_clipboard_sync_status_display"
            isIconSpaceReserved = false
            isSelectable = false
            setTitle(R.string.asrkb_clipboard_sync_status)
        }
        screen.addPreference(statusPreference)
    }

    override fun onStart() {
        super.onStart()
        preferences().registerOnSharedPreferenceChangeListener(statusListener)
        updateStatus()
    }

    override fun onStop() {
        preferences().unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onStop()
    }

    private fun updateStatus() {
        val prefs = preferences()
        if (!prefs.getBoolean(ASRKB_CLIPBOARD_SYNC_ENABLED_KEY, false)) {
            statusPreference.setSummary(R.string.asrkb_clipboard_sync_status_disabled)
            return
        }
        val status = AsrkbClipboardSyncStatus.decode(
            prefs.getString(ASRKB_CLIPBOARD_SYNC_STATUS_KEY, null),
        )
        statusPreference.summary = when (status.phase) {
            AsrkbClipboardSyncPhase.STOPPED -> getString(R.string.asrkb_clipboard_sync_status_stopped)
            AsrkbClipboardSyncPhase.DISABLED -> getString(R.string.asrkb_clipboard_sync_status_stopped)
            AsrkbClipboardSyncPhase.WAITING -> getString(R.string.asrkb_clipboard_sync_status_waiting)
            AsrkbClipboardSyncPhase.CONNECTING -> getString(R.string.asrkb_clipboard_sync_status_connecting)
            AsrkbClipboardSyncPhase.RECONNECTING -> getString(R.string.asrkb_clipboard_sync_status_reconnecting)
            AsrkbClipboardSyncPhase.CONNECTED ->
                getString(R.string.asrkb_clipboard_sync_status_connected, status.detail)
            AsrkbClipboardSyncPhase.OBSERVING ->
                getString(R.string.asrkb_clipboard_sync_status_observing, status.detail)
            AsrkbClipboardSyncPhase.ERROR ->
                getString(R.string.asrkb_clipboard_sync_status_error, status.detail)
        }
    }

    private fun showToggleNotice() {
        val enabled = preferences().getBoolean(ASRKB_CLIPBOARD_SYNC_ENABLED_KEY, false)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.asrkb_clipboard_sync_enabled)
            .setMessage(
                if (enabled) R.string.asrkb_clipboard_sync_enabled_summary
                else R.string.asrkb_clipboard_sync_disabled_notice,
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun preferences(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(requireContext())
}
