/*
 * SPDX-FileCopyrightText: 2026 BryceWG
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.link

import android.media.AudioManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AsrkbRecordingAudioFocusControllerTest :
    FunSpec({
        test("release abandons granted focus exactly once") {
            val gateway = FakeGateway()
            val controller = AsrkbRecordingAudioFocusController(gateway) { }

            controller.acquire() shouldBe true
            controller.release()
            controller.release()

            controller.isHeldForTest() shouldBe false
            gateway.abandoned.size shouldBe 1
        }

        test("repeated acquire releases the previous request") {
            val gateway = FakeGateway()
            val controller = AsrkbRecordingAudioFocusController(gateway) { }

            controller.acquire() shouldBe true
            val firstHandle = gateway.granted.single()
            controller.acquire() shouldBe true

            gateway.abandoned shouldBe listOf(firstHandle)
            controller.isHeldForTest() shouldBe true
        }

        test("failed acquire does not create a lease") {
            val gateway = FakeGateway(grantRequests = false)
            val controller = AsrkbRecordingAudioFocusController(gateway) { }

            controller.acquire() shouldBe false
            controller.release()

            controller.isHeldForTest() shouldBe false
            gateway.abandoned.isEmpty() shouldBe true
        }

        test("focus loss releases the lease and notifies once") {
            val gateway = FakeGateway()
            val losses = mutableListOf<AsrkbRecordingAudioFocusLoss>()
            val controller = AsrkbRecordingAudioFocusController(gateway, losses::add)

            controller.acquire() shouldBe true
            gateway.emit(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            gateway.emit(AudioManager.AUDIOFOCUS_LOSS)

            controller.isHeldForTest() shouldBe false
            losses shouldBe listOf(AsrkbRecordingAudioFocusLoss.TRANSIENT)
            gateway.abandoned.size shouldBe 1
        }

        test("focus changes map only loss events") {
            asrkbRecordingAudioFocusLossFromChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) shouldBe
                AsrkbRecordingAudioFocusLoss.TRANSIENT
            asrkbRecordingAudioFocusLossFromChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) shouldBe
                AsrkbRecordingAudioFocusLoss.MAY_DUCK
            asrkbRecordingAudioFocusLossFromChange(AudioManager.AUDIOFOCUS_LOSS) shouldBe
                AsrkbRecordingAudioFocusLoss.PERMANENT
            asrkbRecordingAudioFocusLossFromChange(AudioManager.AUDIOFOCUS_GAIN) shouldBe null
        }

        test("session release waits for an in-flight acquire and abandons its lease") {
            val requestEntered = CountDownLatch(1)
            val continueRequest = CountDownLatch(1)
            val gateway =
                FakeGateway(
                    requestEntered = requestEntered,
                    continueRequest = continueRequest,
                )
            val controller = AsrkbRecordingAudioFocusController(gateway) { }
            val owner = AsrkbRecordingAudioFocusSessionOwner()

            val acquireThread = thread { owner.acquire(controller) }
            requestEntered.await(5, TimeUnit.SECONDS) shouldBe true
            val releaseThread = thread { owner.release() }
            continueRequest.countDown()
            acquireThread.join(5_000)
            releaseThread.join(5_000)

            acquireThread.isAlive shouldBe false
            releaseThread.isAlive shouldBe false
            owner.owns(controller) shouldBe false
            gateway.abandoned shouldBe gateway.granted
        }

        test("released session does not own a stale focus-loss callback") {
            val first = AsrkbRecordingAudioFocusController(FakeGateway()) { }
            val second = AsrkbRecordingAudioFocusController(FakeGateway()) { }
            val owner = AsrkbRecordingAudioFocusSessionOwner()

            owner.acquire(first) shouldBe true
            owner.release()
            owner.acquire(second) shouldBe true

            owner.owns(first) shouldBe false
            owner.owns(second) shouldBe true
        }
    }) {
    private class FakeGateway(
        private val grantRequests: Boolean = true,
        private val requestEntered: CountDownLatch? = null,
        private val continueRequest: CountDownLatch? = null,
    ) : AsrkbRecordingAudioFocusGateway {
        val granted = mutableListOf<AsrkbRecordingAudioFocusHandle>()
        val abandoned = mutableListOf<AsrkbRecordingAudioFocusHandle>()
        private var listener: ((Int) -> Unit)? = null

        override fun requestFocus(onFocusChange: (Int) -> Unit): AsrkbRecordingAudioFocusHandle? {
            listener = onFocusChange
            requestEntered?.countDown()
            continueRequest?.await(5, TimeUnit.SECONDS)
            if (!grantRequests) return null
            return FakeHandle(granted.size + 1).also(granted::add)
        }

        override fun abandonFocus(handle: AsrkbRecordingAudioFocusHandle) {
            abandoned += handle
        }

        fun emit(change: Int) {
            listener?.invoke(change)
        }
    }

    private data class FakeHandle(
        val id: Int,
    ) : AsrkbRecordingAudioFocusHandle
}
