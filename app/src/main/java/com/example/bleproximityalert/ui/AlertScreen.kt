package com.example.bleproximityalert.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.bleproximityalert.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun AlertScreen(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val alertDevice by viewModel.alertDevice.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val device = alertDevice ?: selectedDevice

    LaunchedEffect(device?.address) {
        delay(10_000L)
        onDismiss()
    }

    val reduceMotion = rememberReduceMotion()
    val color = animateColorAsState(
        targetValue = MaterialTheme.colorScheme.error,
        animationSpec = motionSpec(reduceMotion),
        label = "alertColor"
    ).value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.height(48.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = device?.state?.name?.replace('_', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Proximity warning",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = color
        )
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Please move closer to your headset before the connection is lost.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                val distLine = device?.distanceMeters?.let { dist ->
                    if (dist > 0) "Estimated distance: ~${String.format("%.1f", dist)} m" else "Signal lost. Device may be out of range"
                } ?: "Signal lost. Device may be out of range"
                Text(
                    text = distLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { /* return to monitoring */ onDismiss() }, modifier = Modifier.weight(1f)) {
                Text("Return to Monitoring")
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("Dismiss")
            }
        }
    }
}