package com.semiz.googletvremote.launcher

import android.content.Context
import com.semiz.googletvremote.bluetooth.BluetoothHidService
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class TvAppShortcut(
    val id: String,
    val name: String,
    val packageName: String,
    val dialAppName: String = "",
    val homeSlotIndex: Int = 0,
    val colorHex: Long,
    val pinned: Boolean = true
)

/**
 * Motor Real de Lanzamiento de Apps en 1 Toque (v2.0):
 * NUNCA dispara el micrófono de Google Assistant (0x0221).
 * Utiliza 3 vías reales en orden de prioridad:
 * 1. Dadb (ADB Criptográfico Real puerto 5555): ejecuta `monkey -p <packageName> -c android.intent.category.LEANBACK_LAUNCHER 1`
 *    para abrir instantáneamente DGO, Netflix, Disney+, Prime Video, XuperTV o cualquier APK instalada.
 * 2. DIAL HTTP REST (puerto 8008 de Chromecast/Google TV) para Netflix/YouTube sin requerir ADB.
 * 3. Bluetooth HID Home-Slot (sin Wi-Fi): navega automáticamente al casillero # de la fila de apps del Home y pulsa OK.
 */
class TvAppLauncherEngine(
    private val context: Context,
    private val btHidService: BluetoothHidService
) {
    companion object {
        val DEFAULT_TV_APPS = listOf(
            TvAppShortcut("netflix", "Netflix", "com.netflix.ninja", "Netflix", 0, 0xFFE50914),
            TvAppShortcut("dgo", "DGO", "com.directv.dtvlatam", "", 1, 0xFF0284C7),
            TvAppShortcut("disney", "Disney+", "com.disney.disneyplus", "", 2, 0xFF1E40AF),
            TvAppShortcut("prime", "Prime Video", "com.amazon.amazonvideo.livingroom", "AmazonInstantVideo", 3, 0xFF00A8E1),
            TvAppShortcut("xupertv", "XuperTV", "com.xuper.tv", "", 4, 0xFF7C3AED),
            TvAppShortcut("youtube", "YouTube", "com.google.android.youtube.tv", "YouTube", 5, 0xFFDC2626)
        )
    }

    private fun getOrCreateAdbKeys(): AdbKeyPair {
        val privFile = File(context.filesDir, "adbkey")
        val pubFile = File(context.filesDir, "adbkey.pub")
        if (!privFile.exists() || !pubFile.exists()) {
            AdbKeyPair.generate(privFile, pubFile)
        }
        return AdbKeyPair.read(privFile, pubFile)
    }

    suspend fun launchAppOneTap(
        app: TvAppShortcut,
        tvIpAddress: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanIp = tvIpAddress?.trim().orEmpty()

        // VÍA 1: Si el usuario ingresó la IP del Google TV, lanzamos el packageName directo por Dadb (ADB real autenticado)
        if (cleanIp.isNotEmpty()) {
            // Intentar primero ADB directo con par de claves RSA
            try {
                val keyPair = getOrCreateAdbKeys()
                Dadb.create(cleanIp, 5555, keyPair).use { dadb ->
                    val cmd = "monkey -p ${app.packageName} -c android.intent.category.LEANBACK_LAUNCHER 1"
                    val response = dadb.shell(cmd)
                    if (response.exitCode == 0) {
                        return@withContext Result.success("🚀 Abierto directo: ${app.name} (${app.packageName})")
                    }
                }
            } catch (adbErr: Exception) {
                // Si aún no tiene depuración ADB aceptada, probamos protocolo DIAL HTTP (puerto 8008) si aplica
                if (app.dialAppName.isNotEmpty()) {
                    try {
                        val url = URL("http://$cleanIp:8008/apps/${app.dialAppName}")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 1200
                            readTimeout = 1200
                            doOutput = true
                        }
                        OutputStreamWriter(conn.outputStream).use { it.write("") }
                        val code = conn.responseCode
                        if (code in 200..299) {
                            return@withContext Result.success("🚀 Abierto por DIAL: ${app.name}")
                        }
                    } catch (_: Exception) {}
                }
                return@withContext Result.failure(
                    Exception("No se pudo conectar a $cleanIp:5555. Aceptá el cartel 'Permitir depuración USB' en la pantalla del TV o usá el modo Bluetooth Casillero.")
                )
            }
        }

        // VÍA 2: Modo 100% Bluetooth HID sin activar el micrófono de voz (Navega al casillero # de Tus Apps en el Home)
        btHidService.launchAppViaHomeGridSlot(app.homeSlotIndex)
        Result.success("🔵 BT HID: Abriendo casillero #${app.homeSlotIndex + 1} (${app.name}) sin micrófono")
    }

    /**
     * Escanea en vivo todas las aplicaciones instaladas en el Google TV usando Dadb (`pm list packages -3`)
     */
    suspend fun scanInstalledTvPackages(tvIpAddress: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanIp = tvIpAddress.trim()
        if (cleanIp.isEmpty()) {
            return@withContext Result.failure(Exception("Ingresá la IP de tu Google TV arriba para escanear sus apps instaladas."))
        }
        try {
            val keyPair = getOrCreateAdbKeys()
            Dadb.create(cleanIp, 5555, keyPair).use { dadb ->
                val resp = dadb.shell("pm list packages -3")
                val pkgs = resp.output
                    .lineSequence()
                    .map { it.removePrefix("package:").trim() }
                    .filter { it.isNotEmpty() }
                    .sorted()
                    .toList()
                Result.success(pkgs)
            }
        } catch (e: Exception) {
            Result.failure(Exception("Error al escanear ($cleanIp:5555): verificá que Depuración por Red/ADB esté activa en el TV."))
        }
    }
}
