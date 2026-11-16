package com.semiz.googletvremote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.semiz.googletvremote.bluetooth.BluetoothHidService
import com.semiz.googletvremote.launcher.TvAppLauncherEngine
import com.semiz.googletvremote.launcher.TvAppShortcut
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Motor de Retrorespuesta Sensorial (Sonido Click + Vibración Háptica)
 * Usa ToneGenerator sobre STREAM_MUSIC para que el click se escuche siempre
 * incluso si el teléfono tiene desactivados los sonidos de toque del sistema.
 */
class TactileFeedbackManager(private val context: Context) {
    private var toneGen: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 85)
    } catch (_: Exception) {
        null
    }

    var soundEnabled: Boolean = true

    fun triggerClick(isAppLaunch: Boolean = false) {
        // 1. Sonido de click acústico inmediato
        if (soundEnabled) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                val tone = if (isAppLaunch) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP
                val duration = if (isAppLaunch) 45 else 22
                toneGen?.startTone(tone, duration)
            } catch (_: Exception) {}
        }

        // 2. Pulso de vibración táctil nítido
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(if (isAppLaunch) 35L else 18L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(20L)
            }
        } catch (_: Exception) {}
    }
}

/**
 * Botón con Iluminación Reactiva (Glow Cyan/Blanco + Escala + Sonido Click + Vibración)
 */
@Composable
fun GlowingRemoteButton(
    text: String,
    baseColor: Color,
    glowColor: Color = Color(0xFF38BDF8),
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    fontSize: TextUnit = 13.sp,
    feedback: TactileFeedbackManager,
    isAppLaunch: Boolean = false,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var flashActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val activeHighlight = isPressed || flashActive

    val bgColor by animateColorAsState(
        targetValue = if (activeHighlight) glowColor else baseColor,
        animationSpec = tween(durationMillis = if (activeHighlight) 40 else 220),
        label = "btnBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (activeHighlight) Color.White else Color.White.copy(alpha = 0.22f),
        animationSpec = tween(durationMillis = 80),
        label = "btnBorder"
    )
    val scale by animateFloatAsState(
        targetValue = if (activeHighlight) 0.93f else 1.0f,
        animationSpec = tween(durationMillis = 70),
        label = "btnScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .background(bgColor, shape)
            .border(if (activeHighlight) 2.5.dp else 1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                feedback.triggerClick(isAppLaunch)
                flashActive = true
                scope.launch {
                    delay(170)
                    flashActive = false
                }
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                color = if (activeHighlight) Color(0xFF070A12) else Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = fontSize
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = if (activeHighlight) Color(0xFF0F172A) else Color.White.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var btService: BluetoothHidService
    private lateinit var launcherEngine: TvAppLauncherEngine
    private lateinit var feedbackManager: TactileFeedbackManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        btService.initialize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        btService = BluetoothHidService(this)
        launcherEngine = TvAppLauncherEngine(this, btService)
        feedbackManager = TactileFeedbackManager(this)

        requestBluetoothPermissionsIfNeeded()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                GoogleTvRemoteScreen(btService, launcherEngine, feedbackManager)
            }
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permissionLauncher.launch(perms.toTypedArray())
        } else {
            btService.initialize()
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun GoogleTvRemoteScreen(
    btService: BluetoothHidService,
    launcherEngine: TvAppLauncherEngine,
    feedback: TactileFeedbackManager
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("gtv_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    val btStatus by btService.connectionStatus.collectAsState()
    val connectedDevice by btService.connectedDevice.collectAsState()
    val pairedDevices by btService.pairedDevices.collectAsState()
    val discoveredDevices by btService.discoveredDevices.collectAsState()
    val isScanning by btService.isScanning.collectAsState()

    var apps by remember { mutableStateOf(TvAppLauncherEngine.DEFAULT_TV_APPS) }
    var tvIp by remember { mutableStateOf(prefs.getString("tv_ip", "") ?: "") }
    var soundOn by remember { mutableStateOf(true) }

    // Diálogos
    var showBtModal by remember { mutableStateOf(false) }
    var showAddAppModal by remember { mutableStateOf(false) }
    var showScanPackagesModal by remember { mutableStateOf(false) }
    var scannedPackages by remember { mutableStateOf<List<String>>(emptyList()) }

    var newAppName by remember { mutableStateOf("") }
    var newAppPkg by remember { mutableStateOf("") }
    var newAppSlot by remember { mutableStateOf("1") }
    var actionFeedback by remember { mutableStateOf("Listo · Cada botón ilumina y emite click sonoro/vibración") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070A12))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // BARRA SUPERIOR: TÍTULO + SONIDO CLICK + POWER
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("GOOGLE TV REMOTE PRO v2.1", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text(btStatus, color = if (connectedDevice != null) Color(0xFF34D399) else Color(0xFF38BDF8), fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlowingRemoteButton(
                    text = if (soundOn) "🔊 Click" else "🔇 Mudo",
                    baseColor = Color(0xFF1E293B),
                    glowColor = Color(0xFF38BDF8),
                    feedback = feedback,
                    fontSize = 11.sp,
                    modifier = Modifier.height(38.dp)
                ) {
                    soundOn = !soundOn
                    feedback.soundEnabled = soundOn
                }
                GlowingRemoteButton(
                    text = "⏻ Power",
                    baseColor = Color(0xFFE11D48),
                    glowColor = Color(0xFFFDA4AF),
                    feedback = feedback,
                    fontSize = 12.sp,
                    modifier = Modifier.height(38.dp)
                ) {
                    btService.sendConsumerUsage(0x0030)
                    actionFeedback = "⚡ Comando Power enviado"
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // BOTÓN PRINCIPAL DE CONEXIÓN BLUETOOTH + CONFIGURAR IP / AGREGAR APP
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlowingRemoteButton(
                text = if (connectedDevice != null) "🔵 BT: ${connectedDevice?.name ?: "Conectado"}" else "🔵 Conectar Bluetooth",
                baseColor = if (connectedDevice != null) Color(0xFF059669) else Color(0xFF2563EB),
                glowColor = Color(0xFF60A5FA),
                feedback = feedback,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                btService.refreshPairedDevices()
                showBtModal = true
            }

            GlowingRemoteButton(
                text = "⚙️ IP TV / +App",
                baseColor = Color(0xFF334155),
                glowColor = Color(0xFFFBBF24),
                feedback = feedback,
                fontSize = 12.sp,
                modifier = Modifier.height(46.dp)
            ) {
                showAddAppModal = true
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // SECCIÓN 1: GRILLA PERSONALIZADA DE APPS EN 1 TOQUE CON ILUMINACIÓN Y CLICK SONORO
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (tvIp.isNotBlank()) "⚡ TUS APPS (LANZAMIENTO DIRECTO ADB: $tvIp)" else "⚡ TUS APPS (1 TOQUE · ILUMINACIÓN + CLICK ACTIVO)",
                color = Color(0xFFFBBF24),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(138.dp).fillMaxWidth()
        ) {
            items(apps.filter { it.pinned }) { app ->
                GlowingRemoteButton(
                    text = app.name,
                    subtitle = if (tvIp.isNotBlank()) "Directo" else "Slot #${app.homeSlotIndex + 1}",
                    baseColor = Color(app.colorHex),
                    glowColor = Color(0xFFFDE047), // Destello dorado brillante al tocar una App
                    shape = RoundedCornerShape(14.dp),
                    fontSize = 13.sp,
                    feedback = feedback,
                    isAppLaunch = true,
                    modifier = Modifier.height(60.dp).fillMaxWidth()
                ) {
                    scope.launch {
                        actionFeedback = "🚀 Abriendo ${app.name}..."
                        val res = launcherEngine.launchAppOneTap(app, tvIp)
                        actionFeedback = res.fold(
                            onSuccess = { it },
                            onFailure = { "⚠️ ${it.message}" }
                        )
                    }
                }
            }
        }

        Text(
            text = actionFeedback,
            color = Color(0xFF38BDF8),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        // SECCIÓN 2: CRUCETA D-PAD BLUETOOTH HID CON RETROILUMINACIÓN EN CADA DIRECCIÓN
        Box(
            modifier = Modifier
                .size(206.dp)
                .background(Color(0xFF0F172A), CircleShape)
                .border(2.dp, Color(0xFF334155), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Arriba
            GlowingRemoteButton(
                text = "▲",
                baseColor = Color(0xFF1E293B),
                glowColor = Color(0xFF38BDF8),
                shape = CircleShape,
                fontSize = 20.sp,
                feedback = feedback,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(52.dp)
            ) {
                btService.sendConsumerUsage(0x0042)
                actionFeedback = "▲ D-Pad Arriba"
            }
            // Abajo
            GlowingRemoteButton(
                text = "▼",
                baseColor = Color(0xFF1E293B),
                glowColor = Color(0xFF38BDF8),
                shape = CircleShape,
                fontSize = 20.sp,
                feedback = feedback,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(52.dp)
            ) {
                btService.sendConsumerUsage(0x0043)
                actionFeedback = "▼ D-Pad Abajo"
            }
            // Izquierda
            GlowingRemoteButton(
                text = "◀",
                baseColor = Color(0xFF1E293B),
                glowColor = Color(0xFF38BDF8),
                shape = CircleShape,
                fontSize = 20.sp,
                feedback = feedback,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(52.dp)
            ) {
                btService.sendConsumerUsage(0x0044)
                actionFeedback = "◀ D-Pad Izquierda"
            }
            // Derecha
            GlowingRemoteButton(
                text = "▶",
                baseColor = Color(0xFF1E293B),
                glowColor = Color(0xFF38BDF8),
                shape = CircleShape,
                fontSize = 20.sp,
                feedback = feedback,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(52.dp)
            ) {
                btService.sendConsumerUsage(0x0045)
                actionFeedback = "▶ D-Pad Derecha"
            }
            // Centro OK
            GlowingRemoteButton(
                text = "OK",
                subtitle = "ENTER",
                baseColor = Color(0xFF2563EB),
                glowColor = Color(0xFF67E8F9),
                shape = CircleShape,
                fontSize = 17.sp,
                feedback = feedback,
                modifier = Modifier.size(76.dp)
            ) {
                btService.sendConsumerUsage(0x0041)
                actionFeedback = "⏺ OK / Seleccionar"
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // SECCIÓN 3: BOTONES DE SISTEMA Y VOLUMEN CON DESTELLO Y CLICK
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlowingRemoteButton(
                text = "↩ Atrás",
                baseColor = Color(0xFF334155),
                glowColor = Color(0xFF38BDF8),
                feedback = feedback,
                modifier = Modifier.weight(1f).height(42.dp)
            ) {
                btService.sendConsumerUsage(0x0224)
                actionFeedback = "↩ Atrás"
            }
            GlowingRemoteButton(
                text = "🏠 Inicio",
                baseColor = Color(0xFF334155),
                glowColor = Color(0xFF38BDF8),
                feedback = feedback,
                modifier = Modifier.weight(1f).height(42.dp)
            ) {
                btService.sendConsumerUsage(0x0223)
                actionFeedback = "🏠 Inicio (Home)"
            }
            GlowingRemoteButton(
                text = "⏯ Play",
                baseColor = Color(0xFF334155),
                glowColor = Color(0xFF38BDF8),
                feedback = feedback,
                modifier = Modifier.weight(1f).height(42.dp)
            ) {
                btService.sendConsumerUsage(0x00CD)
                actionFeedback = "⏯ Play / Pausa"
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlowingRemoteButton(
                text = "🔉 Vol -",
                baseColor = Color(0xFF1E293B),
                glowColor = Color(0xFF34D399),
                feedback = feedback,
                modifier = Modifier.weight(1f).height(42.dp)
            ) {
                btService.sendConsumerUsage(0x00EA)
                actionFeedback = "🔉 Bajar Volumen"
            }
            GlowingRemoteButton(
                text = "🔇 Mute",
                baseColor = Color(0xFF1E293B),
                glowColor = Color(0xFFF87171),
                feedback = feedback,
                modifier = Modifier.weight(1f).height(42.dp)
            ) {
                btService.sendConsumerUsage(0x00E2)
                actionFeedback = "🔇 Mute"
            }
            GlowingRemoteButton(
                text = "🔊 Vol +",
                baseColor = Color(0xFF1E293B),
                glowColor = Color(0xFF34D399),
                feedback = feedback,
                modifier = Modifier.weight(1f).height(42.dp)
            ) {
                btService.sendConsumerUsage(0x00E9)
                actionFeedback = "🔊 Subir Volumen"
            }
        }
    }

    // ==================== MODAL 1: ESCÁNER Y CONEXIÓN BLUETOOTH ====================
    if (showBtModal) {
        AlertDialog(
            onDismissRequest = { showBtModal = false },
            title = { Text("🔵 Conectar a Google TV por Bluetooth", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                feedback.triggerClick()
                                btService.startDiscoveryScan()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isScanning) "Buscando..." else "🔍 Buscar TV Cercanos", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = {
                            feedback.triggerClick()
                            btService.openSystemBluetoothSettings()
                        }) {
                            Text("Ajustes BT", fontSize = 12.sp)
                        }
                    }

                    Text("Dispositivos Vinculados (Tocá para conectar):", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF38BDF8))
                    if (pairedDevices.isEmpty()) {
                        Text("No hay dispositivos vinculados aún. Tocá 'Buscar TV Cercanos' o vinculá el TV en Ajustes BT.", fontSize = 11.sp, color = Color.Gray)
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 140.dp)) {
                            items(pairedDevices) { dev ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            feedback.triggerClick(true)
                                            btService.connectToTv(dev)
                                            showBtModal = false
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("📺 ${dev.name ?: dev.address}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Conectar", color = Color(0xFF34D399), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    if (discoveredDevices.isNotEmpty()) {
                        Text("Dispositivos Nuevos Encontrados:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFFBBF24))
                        LazyColumn(modifier = Modifier.heightIn(max = 120.dp)) {
                            items(discoveredDevices) { dev ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            feedback.triggerClick(true)
                                            btService.pairOrConnectDevice(dev)
                                            showBtModal = false
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("🆕 ${dev.name ?: dev.address}", fontSize = 13.sp)
                                    Text("Vincular", color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (connectedDevice != null) {
                    TextButton(onClick = {
                        feedback.triggerClick()
                        btService.disconnectCurrentTv()
                        showBtModal = false
                    }) {
                        Text("Desconectar actual", color = Color(0xFFF87171))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBtModal = false }) {
                    Text("Cerrar")
                }
            }
        )
    }

    // ==================== MODAL 2: CONFIGURAR IP ADB DIRECTA Y AGREGAR CUALQUIER APP ====================
    if (showAddAppModal) {
        AlertDialog(
            onDismissRequest = { showAddAppModal = false },
            title = { Text("⚡ Lanzamiento Directo + Agregar App", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Para abrir Netflix, DGO, Disney+, Prime o XuperTV directo por Package Name (sin pasar por menús), poné la IP de tu Google TV (con Depuración ADB activa):",
                        fontSize = 11.sp,
                        color = Color(0xFFCBD5E1)
                    )
                    OutlinedTextField(
                        value = tvIp,
                        onValueChange = {
                            tvIp = it
                            prefs.edit().putString("tv_ip", it.trim()).apply()
                        },
                        label = { Text("IP de tu Google TV (ej: 192.168.1.50)") },
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            feedback.triggerClick(true)
                            scope.launch {
                                val res = launcherEngine.scanInstalledTvPackages(tvIp)
                                res.onSuccess { list ->
                                    scannedPackages = list
                                    showScanPackagesModal = true
                                }.onFailure { err ->
                                    actionFeedback = "⚠️ ${err.message}"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔍 Escanear Todas las Apps Instaladas en el TV", fontSize = 12.sp, color = Color.White)
                    }

                    Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))

                    Text("Agregar nuevo botón a la grilla:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    OutlinedTextField(
                        value = newAppName,
                        onValueChange = { newAppName = it },
                        label = { Text("Nombre (ej: Flow, Magis, Stremio)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newAppPkg,
                        onValueChange = { newAppPkg = it },
                        label = { Text("Package (ej: com.xuper.tv)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newAppSlot,
                        onValueChange = { newAppSlot = it },
                        label = { Text("Casillero en fila Home (1 = 1ra app, 2 = 2da...)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    feedback.triggerClick(true)
                    if (newAppName.isNotBlank()) {
                        val pkg = newAppPkg.ifBlank { "com.custom.${newAppName.lowercase()}" }
                        val slot = newAppSlot.toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: apps.size
                        apps = apps + TvAppShortcut(
                            id = "custom_${System.currentTimeMillis()}",
                            name = newAppName.trim(),
                            packageName = pkg.trim(),
                            homeSlotIndex = slot,
                            colorHex = 0xFF2563EB
                        )
                        newAppName = ""
                        newAppPkg = ""
                    }
                    showAddAppModal = false
                }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddAppModal = false }) {
                    Text("Cerrar")
                }
            }
        )
    }

    // ==================== MODAL 3: SELECCIONAR CUALQUIER APP ESCANEADA DEL TV ====================
    if (showScanPackagesModal) {
        AlertDialog(
            onDismissRequest = { showScanPackagesModal = false },
            title = { Text("📦 Apps Detectadas en tu Google TV") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(scannedPackages) { pkg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    feedback.triggerClick(true)
                                    val shortName = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                                    apps = apps + TvAppShortcut(
                                        id = "scan_${System.currentTimeMillis()}",
                                        name = shortName,
                                        packageName = pkg,
                                        homeSlotIndex = apps.size,
                                        colorHex = 0xFF0D9488
                                    )
                                    showScanPackagesModal = false
                                    showAddAppModal = false
                                    actionFeedback = "✅ Agregada $shortName ($pkg) al control"
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(pkg, fontSize = 12.sp, color = Color.White)
                            Text("+ Agregar", fontSize = 12.sp, color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScanPackagesModal = false }) {
                    Text("Cerrar")
                }
            }
        )
    }
}
