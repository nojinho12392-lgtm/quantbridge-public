package com.qubit.quantbridge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qubit.quantbridge.ui.theme.QuantGreen

enum class QuantCardRole {
    Decision,
    Information,
    Status
}

enum class QuantPressRole {
    Card,
    Row,
    Icon,
    Text
}

val QuantCardRadius = 28.dp
val QuantControlRadius = 999.dp

@Composable
fun AnimatedPriceText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign? = null
) {
    Row(
        modifier = modifier.animateContentSize(animationSpec = tween(durationMillis = 180)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        text.forEachIndexed { index, character ->
            AnimatedContent(
                targetState = character,
                transitionSpec = {
                    (slideInVertically(animationSpec = tween(durationMillis = 180)) { it / 2 } +
                        fadeIn(animationSpec = tween(durationMillis = 120)))
                        .togetherWith(
                            slideOutVertically(animationSpec = tween(durationMillis = 180)) { -it / 2 } +
                                fadeOut(animationSpec = tween(durationMillis = 120))
                        )
                },
                label = "animated-price-char-$index"
            ) { target ->
                Text(
                    text = target.toString(),
                    style = style,
                    fontWeight = fontWeight,
                    color = color,
                    textAlign = textAlign,
                    maxLines = 1
                )
            }
        }
    }
}

fun Modifier.quantClickable(
    enabled: Boolean = true,
    role: QuantPressRole = QuantPressRole.Row,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by quantPressScale(pressed = pressed, enabled = enabled, role = role)
    val alpha by quantPressAlpha(pressed = pressed, enabled = enabled, role = role)

    this
        .scale(scale)
        .alpha(alpha)
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            role = Role.Button,
            onClick = onClick
        )
}

@Composable
internal fun quantPressScale(pressed: Boolean, enabled: Boolean, role: QuantPressRole) =
    animateFloatAsState(
        targetValue = if (pressed && enabled) {
            when (role) {
                QuantPressRole.Card -> 0.985f
                QuantPressRole.Row -> 0.980f
                QuantPressRole.Icon, QuantPressRole.Text -> 0.964f
            }
        } else {
            1f
        },
        animationSpec = if (pressed) {
            tween(durationMillis = 70, easing = FastOutSlowInEasing)
        } else {
            spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMedium)
        },
        label = "quant-press-scale"
    )

@Composable
internal fun quantPressAlpha(pressed: Boolean, enabled: Boolean, role: QuantPressRole) =
    animateFloatAsState(
        targetValue = if (pressed && enabled) {
            when (role) {
                QuantPressRole.Card -> 0.976f
                QuantPressRole.Row -> 0.964f
                QuantPressRole.Icon, QuantPressRole.Text -> 0.94f
            }
        } else {
            1f
        },
        animationSpec = tween(durationMillis = if (pressed) 90 else 140),
        label = "quant-press-alpha"
    )

@Composable
fun QuantSlidingSegmentSwitch(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(QuantControlRadius)
) {
    val safeOptions = options.ifEmpty { listOf(selected) }
    val selectedIndex = safeOptions.indexOf(selected).let { if (it >= 0) it else 0 }
    val horizontalPadding = 4.dp
    val itemGap = 4.dp

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            val count = safeOptions.size
            val availableWidth = maxWidth - horizontalPadding * 2 - (itemGap.value * (count - 1)).dp
            val segmentWidth = (availableWidth.value / count).dp
            val targetOffset = horizontalPadding + ((segmentWidth.value + itemGap.value) * selectedIndex).dp
            val indicatorOffset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                label = "quant-segment-indicator"
            )

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primary)
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(itemGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                safeOptions.forEachIndexed { index, option ->
                    val isSelected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(shape)
                            .quantClickable(role = QuantPressRole.Text, onClick = { onSelect(option) }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            option,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
