package com.osfans.trime.link

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AsrkbClipboardSyncStateTest : FunSpec({
    test("status round trips with diagnostic detail") {
        val status = AsrkbClipboardSyncStatus(
            AsrkbClipboardSyncPhase.ERROR,
            "Pro=-6, OSS=0",
        )

        AsrkbClipboardSyncStatus.decode(status.encode()) shouldBe status
        AsrkbClipboardSyncStatus.decode("broken").phase shouldBe AsrkbClipboardSyncPhase.STOPPED
    }

    test("remote write suppresses only matching next callback") {
        val suppressor = AsrkbClipboardEchoSuppressor(timeoutMs = 1_000)
        suppressor.expect("remote", nowMs = 100)

        suppressor.shouldSuppress("remote", nowMs = 200) shouldBe true
        suppressor.shouldSuppress("remote", nowMs = 201) shouldBe false
    }

    test("different or expired clipboard does not get suppressed") {
        val suppressor = AsrkbClipboardEchoSuppressor(timeoutMs = 1_000)
        suppressor.expect("remote", nowMs = 100)
        suppressor.shouldSuppress("local", nowMs = 200) shouldBe false

        suppressor.expect("remote", nowMs = 100)
        suppressor.shouldSuppress("remote", nowMs = 1_101) shouldBe false
    }

    test("closing the IME keeps the last connection diagnostic") {
        val failed = AsrkbClipboardSyncStatus(
            AsrkbClipboardSyncPhase.ERROR,
            "Pro=-7, OSS=-6",
        )

        statusAfterWindowHidden(failed) shouldBe failed
    }

    test("host requests require the enabled active session") {
        isClipboardHostRequestAuthorized(true, true, "oss", "oss") shouldBe true
        isClipboardHostRequestAuthorized(false, true, "oss", "oss") shouldBe false
        isClipboardHostRequestAuthorized(true, false, "oss", "oss") shouldBe false
        isClipboardHostRequestAuthorized(true, true, "oss", "pro") shouldBe false
    }

    test("active status preserves an observing subscription") {
        activeSessionPhase(isObserving = true) shouldBe AsrkbClipboardSyncPhase.OBSERVING
        activeSessionPhase(isObserving = false) shouldBe AsrkbClipboardSyncPhase.CONNECTED
    }
})
