package dev.bucket.launcherslogan

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Text

/** The pager owns both the page position and its indicator, including interrupted gestures. */
@Composable
internal fun FloatingMainNavigationBar(
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = remember { listOf("首页" to AppIcons.Home, "设置" to AppIcons.Settings) }
    BoxWithConstraints(
        modifier = modifier.padding(horizontal = 28.dp).widthIn(max = 198.dp).fillMaxWidth()
            .shadow(5.dp, CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.04f),
                spotColor = Color.Black.copy(alpha = 0.08f))
            .clip(CircleShape).background(AppPalette.Surface)
            .border(1.dp, Brush.linearGradient(listOf(Color.White, Color(0xFFB8C0CF),
                Color(0xFFF9FBFF), Color(0xFFC1C8D5))), CircleShape)
            .height(56.dp).padding(4.dp),
    ) {
        val tabWidth = maxWidth / tabs.size
        Box(
            Modifier.graphicsLayer {
                val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, tabs.lastIndex.toFloat())
                translationX = position * tabWidth.toPx()
            }.width(tabWidth).fillMaxHeight().background(Color(0xFFDCE3F7), CircleShape),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            tabs.forEachIndexed { index, (label, icon) ->
                val selected = pagerState.targetPage == index
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) 0.94f else 1f,
                    folmeSpring(damping = 1f, response = 0.22f), label = "tab-press")
                val color by animateColorAsState(if (selected) AppPalette.Primary else AppPalette.Muted,
                    animationSpec = tween(160), label = "tab-color")
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                        .selectable(selected = selected, role = Role.Tab,
                            interactionSource = interaction, indication = null,
                            onClick = { onSelect(index) })
                        .graphicsLayer { scaleX = scale; scaleY = scale },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                ) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(22.dp),
                        colorFilter = ColorFilter.tint(color))
                    Text(label, color = color, fontSize = 11.sp, lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1)
                }
            }
        }
    }
}
