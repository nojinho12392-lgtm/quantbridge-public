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
