package com.example.bleproximityalert.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bleproximityalert.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisconnectHistoryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val history by viewModel.disconnectHistory.collectAsState()
    var showConfirmAll by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        TopAppBar(
            title = { Text("Disconnect History") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (history.isNotEmpty()) {
                    IconButton(onClick = { showConfirmAll = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear History")
                    }
                }
            }
        )

        if (showConfirmAll) {
            AlertDialog(
                onDismissRequest = { showConfirmAll = false },
                title = { Text("Clear all history?") },
                text = { Text("This will delete all disconnect events.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.clearDisconnectHistory()
                        showConfirmAll = false
                    }) { Text("Delete all") }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmAll = false }) { Text("Cancel") }
                }
            )
        }

        if (history.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No disconnection events recorded.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(history) { event ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(event.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                val dateStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(event.timestampMs))
                                Text(dateStr, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Address: ${event.deviceAddress}", style = MaterialTheme.typography.bodySmall)
                            event.lastDistanceMeters?.let {
                                Text("Last Distance: ${String.format("%.1f", it)} m (${event.lastRssi ?: -100} dBm)", style = MaterialTheme.typography.bodySmall)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (event.latitude != null && event.longitude != null) {
                                OutlinedButton(
                                    onClick = {
                                        val uri = Uri.parse("geo:${event.latitude},${event.longitude}?q=${event.latitude},${event.longitude}(${event.deviceName}+Last+Location)")
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Place, contentDescription = null)
                                    Spacer(modifier = Modifier.padding(start = 6.dp))
                                    Text("Open Disconnect Location on Map")
                                }
                            } else {
                                Text("Location unavailable for this event", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.deleteDisconnectEvent(event) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.padding(start = 6.dp))
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}