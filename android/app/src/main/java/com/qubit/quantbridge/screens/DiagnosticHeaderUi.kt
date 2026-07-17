package com.qubit.quantbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qubit.quantbridge.ui.theme.QuantNegative
import com.qubit.quantbridge.ui.theme.QuantPositive
import java.util.Locale
import kotlin.math.min

@Composable
internal fun HeaderCard(
    title: String,
    value: String,
    subtitle: String,
    trailing: String,
    onClick: (() -> Unit)? = null,
    quiet: Boolean = false
) {
    val shape = RoundedCornerShape(24.dp)
    val cardModifier = if (onClick == null) {
        Modifier.fillMaxWidth()
    } else {
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .quantClickable(role = QuantPressRole.Card, onClick = onClick)
    }
    Surface(
        modifier = cardModifier,
        color = if (quiet) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = shape,
        border = BorderStroke(1.dp, if (quiet) MaterialTheme.colorScheme.outline.copy(alpha = 0.28f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer)
                Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = regimeColor(value))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trailing, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (onClick != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun DiagnosticHeaderCard(
    title: String,
    value: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val bodyColor = MaterialTheme.colorScheme.onSurface
    val trailingTone = statusTone(trailing)
    val trailingColor = if (trailingTone == DetailTone.Neutral) labelColor else toneColor(trailingTone)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .quantClickable(role = QuantPressRole.Card, onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = shape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = labelColor)
                Text(
                    value.uppercase(Locale.US),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = toneColor(statusTone(value))
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trailing, style = MaterialTheme.typography.titleSmall, color = trailingColor)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = bodyColor.copy(alpha = 0.55f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
