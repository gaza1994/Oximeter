package com.garethjohnstone.oximeter.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garethjohnstone.oximeter.ble.LinkState

/**
 * What the monitor shows instead of a trace while there is no link. Deliberately
 * quiet: the device list belongs in settings, not on the screen someone glances
 * at from a doorway.
 */
@Composable
fun WaitingPanel(
    link: LinkState,
    status: SystemStatus,
    note: String?,
    onGrantPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val pulse by rememberInfiniteTransition(label = "waiting").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waitingAlpha"
    )

    Column(
        modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (status.allClear) {
            Text(
                text = when (link) {
                    LinkState.Connecting -> "Connecting"
                    LinkState.Reconnecting -> "Reconnecting"
                    LinkState.NoBluetooth -> "Bluetooth is off"
                    LinkState.Idle -> "Stopped"
                    else -> "Looking for the sensor"
                },
                color = ink().Spo2.copy(alpha = pulse),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Switch the sensor on and put the probe on a finger.",
                color = ink().Label,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Column(Modifier.fillMaxWidth()) {
                if (!status.bluetoothOn) {
                    Blocker("Bluetooth is off") { openBluetoothSettings(context) }
                }
                if (!status.permissionsGranted) {
                    Blocker("Permission to scan was not granted", onGrantPermissions)
                }
                if (status.locationMatters && !status.locationServicesOn) {
                    Blocker("Location is off, which stops Bluetooth scanning on many phones") {
                        openLocationSettings(context)
                    }
                }
            }
        }

        if (note != null) {
            Text(
                note,
                color = ink().Caution,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun Blocker(text: String, action: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(ink().Caution.copy(alpha = 0.12f))
            .clickable { action() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = ink().Caution, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("Fix", color = ink().Caution, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/**
 * The picker, now living in settings. Same data, without the urgency.
 */
@Composable
fun DeviceList(
    devices: List<com.garethjohnstone.oximeter.ble.DiscoveredDevice>,
    rememberedAddress: String,
    onPick: (String) -> Unit,
    onRescan: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (devices.isEmpty()) "Nothing seen yet"
                else "${devices.size} device(s) nearby",
                color = ink().Label,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Rescan",
                color = ink().Spo2,
                fontSize = 16.sp,
                modifier = Modifier.clickable { onRescan() }.padding(8.dp)
            )
        }

        devices.take(12).forEach { d ->
            val chosen = d.address.equals(rememberedAddress, ignoreCase = true)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .background(
                        if (d.advertisesTargetService || chosen) ink().Panel
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { onPick(d.address) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        d.name ?: "(no name)",
                        color = if (d.name == null) ink().Faint
                        else MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp
                    )
                    Row {
                        Text(d.address, color = ink().Label, fontSize = 13.sp)
                        if (d.advertisesTargetService) {
                            Text("  FFE0", color = ink().Spo2, fontSize = 13.sp)
                        }
                        if (chosen) {
                            Text("  remembered", color = ink().Caution, fontSize = 13.sp)
                        }
                    }
                }
                Text("${d.rssi}", color = ink().Label, fontSize = 14.sp)
            }
        }
    }
}
