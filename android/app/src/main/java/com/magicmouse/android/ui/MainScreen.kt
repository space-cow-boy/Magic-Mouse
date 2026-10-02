package com.magicmouse.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magicmouse.android.MainViewModel
import com.magicmouse.android.controller.ConnectionState
import com.magicmouse.android.controller.SensorSnapshot
import com.magicmouse.android.network.BluetoothDeviceItem

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val sensorSnapshot  by viewModel.sensorSnapshot.collectAsStateWithLifecycle()
    var sensitivity     by remember { mutableFloatStateOf(viewModel.sensitivity) }
    var isFullscreen    by remember { mutableStateOf(false) }

    val isConnected = connectionState is ConnectionState.Connected

    if (isFullscreen) {
        // Fullscreen Trackpad Mode overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0F))
                .pointerInteropFilter { event ->
                    viewModel.controller.gestureDetector.onTouchEvent(event)
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = Color(0xFF64D2FF).copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Fullscreen Trackpad Active",
                    color = Color(0xFFE5E5EA),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap · Scroll · Drag anywhere",
                    color = Color(0xFF8E8E93),
                    fontSize = 13.sp
                )
            }

            // Floating Exit Fullscreen Button
            IconButton(
                onClick = { isFullscreen = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2C2C2E))
            ) {
                Icon(
                    imageVector = Icons.Default.FullscreenExit,
                    contentDescription = "Exit Fullscreen",
                    tint = Color(0xFFE5E5EA)
                )
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0A0A0F), Color(0xFF12121A))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Header ────────────────────────────────────────────────────────
                AppHeader(connectionState)

                // ── Touch Pad Area with Fullscreen Button ──────────────────────────
                TouchPadArea(
                    isConnected = isConnected,
                    onFullscreenClick = { isFullscreen = true },
                    onTouchEvent = { event ->
                        viewModel.controller.gestureDetector.onTouchEvent(event)
                    }
                )

                // ── Connection Card (Bluetooth HID - No PC App Required) ───────────
                ConnectionCard(
                    connectionState = connectionState,
                    viewModel = viewModel,
                    onConnect = { address, name ->
                        viewModel.connect(address, name)
                    },
                    onDisconnect = { viewModel.disconnect() }
                )

                // ── Controls Row ──────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ControlButton(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.MyLocation,
                        label = "Recenter",
                        onClick = { viewModel.recenter() }
                    )
                    SensitivityCard(
                        modifier = Modifier.weight(2f),
                        sensitivity = sensitivity,
                        onSensitivityChange = {
                            sensitivity = it
                            viewModel.sensitivity = it
                        }
                    )
                }

                // ── Sensor Debug Panel ────────────────────────────────────────────
                SensorDebugPanel(sensorSnapshot)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader(connectionState: ConnectionState) {
    val dotColor by animateColorAsState(
        targetValue = when (connectionState) {
            is ConnectionState.Connected   -> Color(0xFF30D158)
            is ConnectionState.Connecting  -> Color(0xFFFFD60A)
            is ConnectionState.Error       -> Color(0xFFFF453A)
            else                           -> Color(0xFF48484A)
        },
        animationSpec = tween(400),
        label = "dot_color"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "Magic Mouse",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE5E5EA)
            )
            Text(
                text = when (connectionState) {
                    is ConnectionState.Connected  -> "Connected · ${connectionState.deviceName}"
                    is ConnectionState.Connecting -> "Connecting as Mouse…"
                    is ConnectionState.Error      -> "Error: ${connectionState.message}"
                    else                          -> "Ready (No PC app required)"
                },
                fontSize = 13.sp,
                color = Color(0xFF8E8E93)
            )
        }
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TouchPadArea(
    isConnected: Boolean,
    onFullscreenClick: () -> Unit,
    onTouchEvent: (MotionEvent) -> Boolean
) {
    val borderColor by animateColorAsState(
        targetValue = if (isConnected) Color(0xFF64D2FF).copy(alpha = 0.5f) else Color(0xFF3A3A3C),
        animationSpec = tween(400),
        label = "pad_border"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF1C1C1E))
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            .pointerInteropFilter { event -> onTouchEvent(event) },
        contentAlignment = Alignment.Center
    ) {
        if (!isConnected) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = Color(0xFF3A3A3C),
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Connect to activate\ntouch pad",
                    color = Color(0xFF48484A),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = Color(0xFF64D2FF).copy(alpha = 0.4f),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Touch Pad Active",
                    color = Color(0xFF64D2FF).copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
                Text(
                    text = "Tap · Scroll · Drag",
                    color = Color(0xFF48484A),
                    fontSize = 11.sp
                )
            }
        }

        // Fullscreen button in top-right corner of touchpad
        IconButton(
            onClick = onFullscreenClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFF2C2C2E).copy(alpha = 0.8f))
        ) {
            Icon(
                imageVector = Icons.Default.Fullscreen,
                contentDescription = "Fullscreen Trackpad",
                tint = Color(0xFF64D2FF),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConnectionCard(
    connectionState: ConnectionState,
    viewModel: MainViewModel,
    onConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val isConnected   = connectionState is ConnectionState.Connected
    val isConnecting  = connectionState is ConnectionState.Connecting

    var pairedDevices by remember { mutableStateOf<List<BluetoothDeviceItem>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<BluetoothDeviceItem?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            pairedDevices = viewModel.getPairedDevices()
        }
    }

    LaunchedEffect(Unit) {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (hasPermission) {
            pairedDevices = viewModel.getPairedDevices()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    )
                )
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF1C1C1E),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Bluetooth Mouse (HID)", fontWeight = FontWeight.SemiBold, color = Color(0xFF8E8E93), fontSize = 12.sp)
                    Text("No PC app required", fontSize = 10.sp, color = Color(0xFF30D158))
                }
                if (!isConnected) {
                    TextButton(onClick = {
                        pairedDevices = viewModel.getPairedDevices()
                    }) {
                        Text("Refresh", fontSize = 11.sp, color = Color(0xFF64D2FF))
                    }
                }
            }

            if (!isConnected) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFE5E5EA),
                            containerColor = Color(0xFF2C2C2E)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF3A3A3C))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedDevice?.name ?: if (pairedDevices.isEmpty()) "No paired PCs found" else "Select paired PC...",
                                color = if (selectedDevice != null) Color(0xFFE5E5EA) else Color(0xFF8E8E93)
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF8E8E93))
                        }
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(Color(0xFF2C2C2E))
                    ) {
                        if (pairedDevices.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No paired devices found", color = Color(0xFF8E8E93)) },
                                onClick = { expanded = false }
                            )
                        } else {
                            pairedDevices.forEach { device ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(device.name, color = Color(0xFFE5E5EA), fontWeight = FontWeight.Medium)
                                            Text(device.address, color = Color(0xFF8E8E93), fontSize = 11.sp)
                                        }
                                    },
                                    onClick = {
                                        selectedDevice = device
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                val conn = connectionState as ConnectionState.Connected
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2C2C2E))
                        .padding(12.dp)
                ) {
                    Text("Connected to PC as Mouse:", fontSize = 11.sp, color = Color(0xFF8E8E93))
                    Text(conn.deviceName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF30D158))
                    Text(conn.deviceAddress, fontSize = 11.sp, color = Color(0xFF8E8E93))
                }
            }

            Button(
                onClick = {
                    if (isConnected) {
                        onDisconnect()
                    } else {
                        selectedDevice?.let { dev ->
                            onConnect(dev.address, dev.name)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = !isConnecting && (isConnected || selectedDevice != null),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Color(0xFF3A3A3C) else Color(0xFF64D2FF),
                    contentColor   = if (isConnected) Color(0xFFE5E5EA) else Color(0xFF001F2E)
                )
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color(0xFF64D2FF),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        if (isConnected) "Disconnect" else "Connect as Bluetooth Mouse",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ControlButton(modifier: Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF2C2C2E),
            contentColor   = Color(0xFFE5E5EA)
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SensitivityCard(modifier: Modifier, sensitivity: Float, onSensitivityChange: (Float) -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF2C2C2E),
        modifier = modifier.height(52.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Sensitivity", fontSize = 11.sp, color = Color(0xFF8E8E93))
                Text("${sensitivity.toInt()}", fontSize = 11.sp, color = Color(0xFF64D2FF), fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = sensitivity,
                onValueChange = onSensitivityChange,
                valueRange = 5f..40f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor        = Color(0xFF64D2FF),
                    activeTrackColor  = Color(0xFF64D2FF),
                    inactiveTrackColor = Color(0xFF3A3A3C)
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SensorDebugPanel(snapshot: SensorSnapshot) {
    var expanded by remember { mutableStateOf(true) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF1C1C1E),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sensor Debug", fontWeight = FontWeight.SemiBold, color = Color(0xFF8E8E93), fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${snapshot.packetsSent} reports", fontSize = 11.sp, color = Color(0xFF30D158))
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = Color(0xFF8E8E93)
                        )
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                SensorRow("Gyro", snapshot.gyroX, snapshot.gyroY, snapshot.gyroZ, "rad/s", Color(0xFFFF9F0A))
                Spacer(Modifier.height(8.dp))
                SensorRow("Accel", snapshot.accelX, snapshot.accelY, snapshot.accelZ, "m/s²", Color(0xFF64D2FF))
                Spacer(Modifier.height(8.dp))
                SensorRow("Orientation", snapshot.pitch, snapshot.yaw, snapshot.roll, "°",   Color(0xFF30D158))
                Spacer(Modifier.height(8.dp))

                OrientationVisualizer(pitch = snapshot.pitch, yaw = snapshot.yaw)

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MonoValue("ΔX", snapshot.cursorDx, Color(0xFFFF375F))
                    MonoValue("ΔY", snapshot.cursorDy, Color(0xFFFF375F))
                }
            }
        }
    }
}

@Composable
private fun SensorRow(label: String, x: Float, y: Float, z: Float, unit: String, color: Color) {
    Column {
        Text(label, fontSize = 11.sp, color = Color(0xFF8E8E93))
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AxisBar("X", x, color)
            AxisBar("Y", y, color)
            AxisBar("Z", z, color)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MonoValue("X", x, color)
            MonoValue("Y", y, color)
            MonoValue("Z", z, color)
        }
    }
}

@Composable
private fun RowScope.AxisBar(axis: String, value: Float, color: Color) {
    val clamped = (value / 20f).coerceIn(-1f, 1f)
    Canvas(modifier = Modifier.weight(1f).height(4.dp)) {
        val w = size.width; val h = size.height
        drawLine(Color(0xFF3A3A3C), Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = h, cap = StrokeCap.Round)
        val center = w / 2f
        val end = center + clamped * center
        drawLine(color.copy(alpha = 0.8f), Offset(center, h / 2f), Offset(end, h / 2f), strokeWidth = h, cap = StrokeCap.Round)
    }
}

@Composable
private fun MonoValue(label: String, value: Float, color: Color) {
    Text(
        text = "$label: ${"%+.3f".format(value)}",
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = color.copy(alpha = 0.9f)
    )
}

@Composable
private fun OrientationVisualizer(pitch: Float, yaw: Float) {
    val clampedX = (yaw   / 45f).coerceIn(-1f, 1f)
    val clampedY = (pitch / 45f).coerceIn(-1f, 1f)
    val dotColor = Color(0xFF64D2FF)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0A0F))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val cx = w / 2f + clampedX * (w / 2f - 16.dp.toPx())
            val cy = h / 2f + clampedY * (h / 2f - 8.dp.toPx())

            drawLine(Color(0xFF2C2C2E), Offset(w / 2f, 0f), Offset(w / 2f, h), 1f)
            drawLine(Color(0xFF2C2C2E), Offset(0f, h / 2f), Offset(w, h / 2f), 1f)

            drawCircle(dotColor.copy(alpha = 0.25f), radius = 18f, center = Offset(cx, cy))
            drawCircle(dotColor, radius = 6f, center = Offset(cx, cy))
        }
        Text(
            text = "Yaw: ${"%.1f".format(yaw)}°  Pitch: ${"%.1f".format(pitch)}°",
            fontSize = 10.sp,
            color = Color(0xFF48484A),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
        )
    }
}
