package com.osfans.trime.link

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class AsrkbExternalInputSessionTest :
    FunSpec({
        test("successful commit tracks the last edited snapshot until clear") {
            val tracker = AsrkbCorrectionTracker(timeoutMs = 1_000)
            tracker.start(
                sessionId = 8,
                generation = 2,
                initial = AsrkbCursorSnapshot("before ", " after"),
                finalText = "Tory",
                committed = AsrkbCursorSnapshot("before Tory", " after"),
                nowMs = 0
            ) shouldBe true

            tracker.update(2, AsrkbCursorSnapshot("before Tauri", " after"), 100) shouldBe null
            tracker.update(2, AsrkbCursorSnapshot("", ""), 200) shouldBe
                AsrkbCorrectionReport(8, 2, AsrkbCursorSnapshot("before Tauri", " after"), "cleared")
        }

        test("failed commit verification and generation changes do not report") {
            val tracker = AsrkbCorrectionTracker(timeoutMs = 10)
            tracker.start(1, 1, AsrkbCursorSnapshot("", ""), "Tory", AsrkbCursorSnapshot("", ""), 0) shouldBe false
            tracker.start(1, 1, AsrkbCursorSnapshot("", ""), "Tory", AsrkbCursorSnapshot("Tory", ""), 0) shouldBe true
            tracker.update(2, AsrkbCursorSnapshot("Tauri", ""), 20) shouldBe null
        }

        test("finish event reports the last trusted snapshot") {
            val tracker = AsrkbCorrectionTracker(timeoutMs = 1_000)
            tracker.start(
                8,
                2,
                AsrkbCursorSnapshot("before ", " after"),
                "Tory",
                AsrkbCursorSnapshot("before Tory", " after"),
                0
            ) shouldBe true
            tracker.update(2, AsrkbCursorSnapshot("before Tauri", " after"), 100) shouldBe null

            tracker.finishLast(2, "editor_action") shouldBe
                AsrkbCorrectionReport(
                    8,
                    2,
                    AsrkbCursorSnapshot("before Tauri", " after"),
                    "editor_action"
                )
            tracker.finishLast(2, "editor_action") shouldBe null
        }

        test("finish event can capture an immediate edit without waiting for update") {
            val tracker = AsrkbCorrectionTracker()
            tracker.start(8, 2, AsrkbCursorSnapshot("", ""), "Tory", AsrkbCursorSnapshot("Tory", ""), 0) shouldBe true

            tracker.finish(2, AsrkbCursorSnapshot("Tauri", ""), "editor_action") shouldBe
                AsrkbCorrectionReport(8, 2, AsrkbCursorSnapshot("Tauri", ""), "editor_action")
        }

        test("unchanged text does not produce a report") {
            val tracker = AsrkbCorrectionTracker()
            tracker.start(
                8,
                2,
                AsrkbCursorSnapshot("before ", " after"),
                "Tory",
                AsrkbCursorSnapshot("before Tory", " after"),
                0
            ) shouldBe true

            tracker.finishLast(2, "finish_input") shouldBe null
        }

        test("editor generation follows input lifecycle and preserves same-editor restart") {
            val tracker = AsrkbEditorGenerationTracker()
            val first = AsrkbEditorIdentity("pkg", 1, 1, 0)
            val second = first.copy(fieldId = 2)

            tracker.onStartInput(first, restarting = false) shouldBe 1
            tracker.onStartInput(first, restarting = true) shouldBe 1
            tracker.onStartInput(second, restarting = true) shouldBe 2
            tracker.onFinishInput() shouldBe 3
            tracker.onStartInput(second, restarting = true) shouldBe 4
        }

        test("unknown optional transaction still continues original ASR") {
            var attachCalled = false
            var originalStarted = false
            val correctionEnabled = negotiateOptionalInputThenContinue(
                queryRequirements = { null },
                attachInputContext = {
                    attachCalled = true
                    true
                },
                continueOriginalAsr = { originalStarted = true }
            )

            correctionEnabled shouldBe false
            attachCalled shouldBe false
            originalStarted shouldBe true
        }

        test("edit report is dispatched off the caller thread") {
            val caller = Thread.currentThread()
            var reportThread: Thread? = null
            val order = mutableListOf<String>()
            runBlocking {
                launchAsrkbEditReport(
                    this,
                    AsrkbCorrectionReport(1, 1, AsrkbCursorSnapshot("a", ""), "finish_input"),
                    submit = {
                        reportThread = Thread.currentThread()
                        order += "submit"
                    },
                    onComplete = { order += "complete" }
                ).join()
            }

            (reportThread !== caller) shouldBe true
            order shouldBe listOf("submit", "complete")
        }

        test("stale negotiation token is rejected") {
            val gate = AsrkbNegotiationGate()
            val connection = Any()
            val stale = gate.begin(1, 1, connection)

            gate.begin(2, 2, connection)

            gate.isCurrent(stale) shouldBe false
        }

        test("privacy gate rejects sensitive editors") {
            AsrkbEditorPrivacy.isEligible(1, 0) shouldBe true
            AsrkbEditorPrivacy.isEligible(0x81, 0) shouldBe false
            AsrkbEditorPrivacy.isEligible(1, 0x01000000) shouldBe false
        }
    })
