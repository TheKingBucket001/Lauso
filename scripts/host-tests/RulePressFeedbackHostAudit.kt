package dev.bucket.launcherslogan

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield

/** Executes the real production interaction policy with a deterministic frame clock. */
private class PressAuditClock : MonotonicFrameClock {
    private val pending = mutableListOf<Pair<CancellableContinuation<Long>, (Long) -> Unit>>()
    private var nanos = 0L

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        var result: R? = null
        suspendCancellableCoroutine<Long> { continuation ->
            pending += continuation to { frame -> result = onFrame(frame) }
        }
        @Suppress("UNCHECKED_CAST")
        return result as R
    }

    suspend fun advance(frames: Int = 1) {
        repeat(frames) {
            yield()
            nanos += 8_333_333L
            val ready = pending.toList()
            pending.clear()
            ready.forEach { (continuation, onFrame) ->
                if (continuation.isActive) {
                    onFrame(nanos)
                    continuation.resume(nanos)
                }
            }
            yield()
        }
    }
}

private class PressAuditHarness(parent: CoroutineScope) {
    val clock = PressAuditClock()
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    val scope = CoroutineScope(parent.coroutineContext + owner + clock)
    val feedback = RulePressFeedback(scope)
    fun close() { feedback.clear(); owner.cancel() }
}

fun main() = runBlocking {
    var passed = 0
    fun verify(condition: Boolean, label: String) {
        check(condition) { label }
        passed++
        println("PASS $label")
    }
    fun near(actual: Float, expected: Float) = abs(actual - expected) < 0.0001f
    fun press() = PressInteraction.Press(Offset.Zero)

    val quick = PressAuditHarness(this)
    try {
        val down = press()
        verify(near(quick.feedback.alpha, 0f), "new feedback starts transparent")
        quick.feedback.accept(down)
        verify(near(quick.feedback.alpha, 0.10f), "press tint appears immediately before any frame")
        quick.feedback.accept(PressInteraction.Release(down))
        verify(near(quick.feedback.alpha, 0.10f), "quick release preserves visible tint before its first frame")
        quick.clock.advance(7)
        verify(quick.feedback.alpha > 0f && quick.feedback.alpha < 0.10f,
            "actual release animation fades smoothly during its 120 millisecond interval")
        quick.clock.advance(20)
        verify(near(quick.feedback.alpha, 0f), "released tint reaches zero after its animation")
    } finally { quick.close() }

    val cancel = PressAuditHarness(this)
    try {
        val down = press()
        cancel.feedback.accept(down)
        cancel.feedback.accept(PressInteraction.Cancel(down))
        verify(near(cancel.feedback.alpha, 0f), "scroll cancellation clears the press tint synchronously")
        cancel.clock.advance(30)
        verify(near(cancel.feedback.alpha, 0f), "cancelled press does not reappear on later frames")
    } finally { cancel.close() }

    val takeover = PressAuditHarness(this)
    try {
        val first = press()
        val second = press()
        takeover.feedback.accept(first)
        takeover.feedback.accept(PressInteraction.Release(first))
        takeover.clock.advance(5)
        takeover.feedback.accept(second)
        verify(near(takeover.feedback.alpha, 0.10f), "new press immediately interrupts an old release fade")
        takeover.clock.advance(30)
        verify(near(takeover.feedback.alpha, 0.10f), "cancelled release job cannot fade a newer held press")
        takeover.feedback.accept(PressInteraction.Release(first))
        takeover.feedback.accept(PressInteraction.Cancel(first))
        verify(near(takeover.feedback.alpha, 0.10f), "stale release and cancel tokens cannot erase a newer press")
        takeover.feedback.accept(PressInteraction.Cancel(second))
        verify(near(takeover.feedback.alpha, 0f), "current press cancellation still clears after stale tokens")
    } finally { takeover.close() }

    val clear = PressAuditHarness(this)
    try {
        val down = press()
        clear.feedback.accept(down)
        clear.feedback.accept(PressInteraction.Release(down))
        clear.clock.advance(4)
        clear.feedback.clear()
        verify(near(clear.feedback.alpha, 0f), "clear removes an active release tint immediately")
        clear.clock.advance(30)
        verify(near(clear.feedback.alpha, 0f), "cleared release animation cannot restore old tint")
        clear.feedback.accept(PressInteraction.Release(down))
        clear.feedback.accept(PressInteraction.Cancel(down))
        verify(near(clear.feedback.alpha, 0f), "cleared feedback ignores old press tokens")
        val next = press()
        clear.feedback.accept(next)
        verify(near(clear.feedback.alpha, 0.10f), "cleared state accepts a fresh press without residue")
        clear.feedback.clear()
    } finally { clear.close() }

    val rest = PressAuditHarness(this)
    try {
        val hover = HoverInteraction.Enter()
        val focus = FocusInteraction.Focus()
        rest.feedback.accept(hover)
        verify(near(rest.feedback.alpha, 0.06f), "hover uses its restrained resting tint")
        rest.feedback.accept(focus)
        verify(near(rest.feedback.alpha, 0.14f), "hover and focus tints compose within the valid alpha range")
        val down = press()
        rest.feedback.accept(down)
        verify(near(rest.feedback.alpha, 0.24f), "press adds emphasis above hover and focus")
        rest.feedback.accept(PressInteraction.Cancel(down))
        verify(near(rest.feedback.alpha, 0.14f), "press cancellation restores hover and focus instead of erasing them")
        rest.feedback.accept(HoverInteraction.Exit(hover))
        verify(near(rest.feedback.alpha, 0.08f), "hover exit preserves the focused tint")
        rest.feedback.accept(FocusInteraction.Unfocus(focus))
        verify(near(rest.feedback.alpha, 0f), "last resting interaction leaving restores transparency")
        rest.feedback.accept(focus)
        rest.feedback.accept(down)
        rest.feedback.accept(PressInteraction.Release(down))
        rest.clock.advance(30)
        verify(near(rest.feedback.alpha, 0.08f), "release settles to the current focused resting tint")
        rest.feedback.clear()
        verify(near(rest.feedback.alpha, 0f), "clear removes focus and hover resting state as well")
    } finally { rest.close() }

    val owner = PressAuditHarness(this)
    val down = press()
    owner.feedback.accept(down)
    owner.feedback.accept(PressInteraction.Release(down))
    owner.close()
    owner.clock.advance(30)
    verify(near(owner.feedback.alpha, 0f), "owner cancellation after clear leaves no animated residue")
    println("PASS RulePressFeedback total=$passed; production source and real Compose animation; headless interaction policy only")
}
