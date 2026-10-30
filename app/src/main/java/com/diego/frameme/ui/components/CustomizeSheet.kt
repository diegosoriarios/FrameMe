package com.diego.frameme.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.diego.frameme.config.FrameConfig
import com.diego.frameme.config.FramePreset
import com.diego.frameme.config.FrameStyle
import com.diego.frameme.config.MatColor
import kotlin.math.roundToInt

/**
 * Bottom-sheet customization panel: photo picker trigger, physical size
 * presets, custom dimension sliders, moulding style and mat board controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeSheet(
    onDismissRequest: () -> Unit,
    config: FrameConfig,
    selectedPhotoUri: Uri?,
    framePlaced: Boolean,
    onConfigChange: (FrameConfig) -> Unit,
    onPickPhoto: () -> Unit,
    onDeleteFrame: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhotoSection(
                selectedPhotoUri = selectedPhotoUri,
                onPickPhoto = onPickPhoto,
            )

            Text("Size", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FramePreset.entries) { preset ->
                    FilterChip(
                        selected = config.preset == preset,
                        onClick = { onConfigChange(config.copy(widthCm = preset.widthCm, heightCm = preset.heightCm)) },
                        label = { Text(preset.label) },
                    )
                }
            }
            if (config.preset == FramePreset.CUSTOM) {
                DimensionSlider(
                    label = "Width",
                    valueCm = config.widthCm,
                    onValueChange = { onConfigChange(config.copy(widthCm = it)) },
                )
                DimensionSlider(
                    label = "Height",
                    valueCm = config.heightCm,
                    onValueChange = { onConfigChange(config.copy(heightCm = it)) },
                )
            }

            Text("Frame style", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FrameStyle.entries.forEach { style ->
                    FilterChip(
                        selected = config.style == style,
                        onClick = { onConfigChange(config.copy(style = style)) },
                        label = { Text(style.label) },
                    )
                }
            }
            DimensionSlider(
                label = "Moulding width",
                valueCm = config.mouldingThicknessCm,
                range = FrameConfig.MIN_MOULDING_CM..FrameConfig.MAX_MOULDING_CM,
                onValueChange = { onConfigChange(config.copy(mouldingThicknessCm = it)) },
            )
            DimensionSlider(
                label = "Moulding depth",
                valueCm = config.mouldingDepthCm,
                range = FrameConfig.MIN_DEPTH_CM..FrameConfig.MAX_DEPTH_CM,
                onValueChange = { onConfigChange(config.copy(mouldingDepthCm = it)) },
            )

            Text("Mat board", style = MaterialTheme.typography.titleSmall)
            DimensionSlider(
                label = "Mat width",
                valueCm = config.matWidthCm,
                range = FrameConfig.MIN_MAT_CM..FrameConfig.MAX_MAT_CM,
                onValueChange = { onConfigChange(config.copy(matWidthCm = it)) },
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MatColor.entries) { mat ->
                    ColorSwatch(
                        color = mat.color,
                        selected = config.matColor == mat,
                        label = mat.label,
                        onClick = { onConfigChange(config.copy(matColor = mat)) },
                    )
                }
            }

            if (framePlaced) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDeleteFrame) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Text("  Remove frame")
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoSection(selectedPhotoUri: Uri?, onPickPhoto: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(if (selectedPhotoUri == null) "No photo selected" else "Photo selected")
        },
        leadingContent = {
            if (selectedPhotoUri != null) {
                AsyncImage(
                    model = selectedPhotoUri,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                )
            } else {
                Icon(Icons.Filled.AddAPhoto, contentDescription = null)
            }
        },
        trailingContent = {
            Button(onClick = onPickPhoto) {
                Text(if (selectedPhotoUri == null) "Choose" else "Change")
            }
        },
    )
}

@Composable
private fun DimensionSlider(
    label: String,
    valueCm: Float,
    range: ClosedFloatingPointRange<Float> = FrameConfig.MIN_SIZE_CM..FrameConfig.MAX_SIZE_CM,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${valueCm.roundToInt()} cm", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = valueCm,
            valueRange = range,
            onValueChange = onValueChange,
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(color, CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = label,
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
            )
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
