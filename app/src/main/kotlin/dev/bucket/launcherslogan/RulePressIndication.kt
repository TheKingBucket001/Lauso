package dev.bucket.launcherslogan

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A draw-only response: scrolling cancels the tint without completing a press animation. */
internal object RulePressIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): Modifier.Node = RulePressNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = javaClass.hashCode()
}

private class RulePressNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private var feedback: RulePressFeedback? = null

    override fun onAttach() {
        val current = RulePressFeedback(coroutineScope)
        feedback = current
        coroutineScope.launch { source.interactions.collect(current::accept) }
    }

    override fun onDetach() {
        feedback?.clear()
        feedback = null
    }

    override fun onReset() {
        feedback?.clear()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = feedback?.alpha ?: 0f
        if (alpha > 0f) drawRect(Color(0xFF1E63D3), alpha = alpha)
    }
}

/** Interaction policy kept separate from its draw node for cancellation checks on JVM. */
internal class RulePressFeedback(private val scope: CoroutineScope) {
    var alpha by mutableFloatStateOf(0f)
        private set
    private var press: PressInteraction.Press? = null
    private var hovered = false
    private var focused = false
    private var release: Job? = null
    private val restingAlpha get() = (if (hovered) 0.06f else 0f) + (if (focused) 0.08f else 0f)

    fun accept(interaction: Interaction) {
        when (interaction) {
            is PressInteraction.Press -> press = interaction
            is PressInteraction.Release -> {
                if (press !== interaction.press) return
                press = null
                release?.cancel()
                release = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    animate(alpha, restingAlpha, animationSpec = tween(120)) { value, _ -> alpha = value }
                }
                return
            }
            is PressInteraction.Cancel -> {
                if (press !== interaction.press) return
                press = null
            }
            is HoverInteraction.Enter -> hovered = true
            is HoverInteraction.Exit -> hovered = false
            is FocusInteraction.Focus -> focused = true
            is FocusInteraction.Unfocus -> focused = false
            else -> return
        }
        release?.cancel()
        release = null
        alpha = restingAlpha + if (press != null) 0.10f else 0f
    }

    fun clear() {
        release?.cancel()
        release = null
        press = null
        hovered = false
        focused = false
        alpha = 0f
    }
}
