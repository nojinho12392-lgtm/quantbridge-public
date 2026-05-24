package com.example.myapplication

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
import com.example.myapplication.ui.theme.QuantGreen

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
private fun quantPressScale(pressed: Boolean, enabled: Boolean, role: QuantPressRole) =
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
private fun quantPressAlpha(pressed: Boolean, enabled: Boolean, role: QuantPressRole) =
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

@Composable
fun QuantCard(
    modifier: Modifier = Modifier,
    role: QuantCardRole = QuantCardRole.Information,
    padding: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val borderColor = when (role) {
        QuantCardRole.Decision -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        QuantCardRole.Information -> MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
        QuantCardRole.Status -> MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    }
    val containerColor = when (role) {
        QuantCardRole.Status -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        shape = RoundedCornerShape(QuantCardRadius),
        border = BorderStroke(if (role == QuantCardRole.Decision) 0.7.dp else 1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = when (role) {
            QuantCardRole.Decision -> 4.dp
            QuantCardRole.Information -> 2.dp
            QuantCardRole.Status -> 0.dp
        }
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun QuantActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    complete: Boolean = false,
    content: (@Composable RowScope.() -> Unit)? = null
) {
    val container = if (complete) QuantGreen else MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by quantPressScale(pressed = pressed, enabled = enabled && !loading, role = QuantPressRole.Row)
    Button(
        onClick = onClick,
        modifier = modifier
            .scale(pressScale)
            .defaultMinSize(minHeight = 36.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(QuantControlRadius),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = MaterialTheme.colorScheme.onPrimary),
        contentPadding = ButtonDefaults.ContentPadding,
        interactionSource = interactionSource
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(7.dp))
        }
        if (content != null) {
            content()
        } else {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun QuantIconActionButton(
    icon: LucideIcon,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(QuantControlRadius))
            .background(tint.copy(alpha = if (enabled) 0.10f else 0.05f))
            .quantClickable(enabled = enabled, role = QuantPressRole.Icon, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        LucideIconView(
            icon = icon,
            contentDescription = contentDescription,
            tint = tint.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun DecisionMetricChip(
    value: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        value,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun SkeletonLoadingCard(
    modifier: Modifier = Modifier,
    lineCount: Int = 3
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QuantCardRadius),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SkeletonBlock(width = 36.dp, height = 36.dp, shape = CircleShape)
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    SkeletonBlock(width = 132.dp, height = 14.dp)
                    SkeletonBlock(width = 94.dp, height = 10.dp)
                }
            }
            repeat(lineCount) { index ->
                SkeletonBlock(width = if (index == lineCount - 1) 160.dp else null, height = 12.dp)
            }
        }
    }
}

@Composable
fun SkeletonBlock(
    width: Dp? = null,
    height: Dp,
    shape: Shape = RoundedCornerShape(5.dp)
) {
    Spacer(
        modifier = Modifier
            .then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
    )
}

@Composable
fun RefreshingStatusPill(label: String = "갱신 중") {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun CompactMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
