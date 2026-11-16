package com.semiz.googletvremote

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.semiz.googletvremote.bluetooth.BluetoothHidService
import com.semiz.googletvremote.launcher.TvAppLauncherEngine
import com.semiz.googletvremote.launcher.TvAppShortcut
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var btService: BluetoothHidService
    private lateinit var launcherEngine: TvAppLauncherEngine

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        btService.initialize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        btService = BluetoothHidService(this)
        launcherEngine = TvAppLauncherEngine(btService)

        requestBluetoothPermissionsIfNeeded()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                GoogleTvRemoteScreen(btService, launcherEngine)
            }
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            val missing = perms.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing) {
                permissionLauncher.launch(perms)
            } else {
                btService.initialize()
            }
        } else {
            btService.initialize()
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun GoogleTvRemoteScreen(
    btService: BluetoothHidService,
    launcherEngine: TvAppLauncherEngine
) {
    val scope = rememberCoroutineScope()
    val btStatus by btService.connectionStatus.collectAsState()
    val pairedDevices by btService.pairedDevices.collectAsState()

    var apps by remember { mutableStateOf(TvAppLauncherEngine.DEFAULT_TV_APPS) }
    var tvIp by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var newAppName by remember { mutableStateOf("") }
    var newAppPkg by remember { mutableStateOf("") }
    var actionFeedback by remember { mutableStateOf("Tocá cualquier app para abrirla en 1 toque") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070A12))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Cabecera y Estado Bluetooth
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("GOOGLE TV REMOTE PRO", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
                Text(btStatus, color = Color(0xFF38BDF8), fontSize = 11.sp)
            }
            Button(
                onClick = { btService.sendConsumerUsage(0x0030) }, // Power
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("⏻", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Selector rápido de TV vinculado por Bluetooth
        if (pairedDevices.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                items(pairedDevices) { device ->
                    OutlinedButton(
                        onClick = { btService.connectToTv(device) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("📺 ${device.name ?: device.address}", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }

        // SECCIÓN 1: GRILLA DE APPS EN 1 TOQUE (DGO, NETFLIX, DISNEY+, PRIME, XUPERTV, + CUALQUIER APP)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚡ TUS APPS (1 TOQUE)",
                color = Color(0xFFFBBF24),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
            TextButton(onClick = { showAddDialog = true }) {
                Text("+ Agregar App / APK", color = Color(0xFF60A5FA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(142.dp).fillMaxWidth()
        ) {
            items(apps.filter { it.pinned }) { app ->
                Box(
                    modifier = Modifier
                        .height(62.dp)
                        .background(Color(app.colorHex), RoundedCornerShape(14.dp))
                        .clickable {
                            scope.launch {
                                val res = launcherEngine.launchAppOneTap(
                                    app = app,
                                    tvIpAddress = tvIp.takeIf { it.isNotBlank() },
                                    preferDirectIntent = tvIp.isNotBlank()
                                )
                                actionFeedback = res.getOrDefault("Abriendo ${app.name}")
                            }
                        }
                        .padding(6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.name,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Text(
            text = actionFeedback,
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            modifier = Modifier.padding(vertical = 6.dp)
        )

        // SECCIÓN 2: CRUCETA D-PAD BLUETOOTH HID COMPLETA
        Box(
            modifier = Modifier
                .size(210.dp)
                .background(Color(0xFF1E293B), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Arriba
            TextButton(
                onClick = { btService.sendConsumerUsage(0x0042) },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
            ) {
                Text("▲", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            // Abajo
            TextButton(
                onClick = { btService.sendConsumerUsage(0x0043) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
            ) {
                Text("▼", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            // Izquierda
            TextButton(
                onClick = { btService.sendConsumerUsage(0x0044) },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
            ) {
                Text("◀", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            // Derecha
            TextButton(
                onClick = { btService.sendConsumerUsage(0x0045) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
            ) {
                Text("▶", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            // Centro OK
            Button(
                onClick = { btService.sendConsumerUsage(0x0041) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                modifier = Modifier.size(78.dp)
            ) {
                Text("OK", fontWeight = FontWeight.Black, fontSize = 17.sp, color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SECCIÓN 3: BOTONES DE SISTEMA Y VOLUMEN
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { btService.sendConsumerUsage(0x0224) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))) {
                Text("↩ Atrás", color = Color.White, fontSize = 12.sp)
            }
            Button(onClick = { btService.sendConsumerUsage(0x0223) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))) {
                Text("🏠 Inicio", color = Color.White, fontSize = 12.sp)
            }
            Button(onClick = { btService.sendConsumerUsage(0x0221) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))) {
                Text("🎙️ Buscar", color = Color.White, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { btService.sendConsumerUsage(0x00EA) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))) {
                Text("🔉 Vol -", color = Color.White, fontSize = 12.sp)
            }
            Button(onClick = { btService.sendConsumerUsage(0x00E2) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))) {
                Text("🔇 Mute", color = Color.White, fontSize = 12.sp)
            }
            Button(onClick = { btService.sendConsumerUsage(0x00E9) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))) {
                Text("🔊 Vol +", color = Color.White, fontSize = 12.sp)
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Agregar App Personalizada") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newAppName,
                        onValueChange = { newAppName = it },
                        label = { Text("Nombre (ej: Flow, Stremio, Magis)") }
                    )
                    OutlinedTextField(
                        value = newAppPkg,
                        onValueChange = { newAppPkg = it },
                        label = { Text("Package (ej: com.xuper.tv)") }
                    )
                    OutlinedTextField(
                        value = tvIp,
                        onValueChange = { tvIp = it },
                        label = { Text("IP del TV (Opcional p/ Intent Directo)") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newAppName.isNotBlank()) {
                        val pkg = newAppPkg.ifBlank { "com.custom.${newAppName.lowercase()}" }
                        apps = apps + TvAppShortcut(
                            id = "custom_${System.currentTimeMillis()}",
                            name = newAppName.trim(),
                            packageName = pkg.trim(),
                            colorHex = 0xFF2563EB
                        )
                        newAppName = ""
                        newAppPkg = ""
                        showAddDialog = false
                    }
                }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
