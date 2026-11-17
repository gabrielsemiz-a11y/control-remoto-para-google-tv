package com.semiz.googletvremote.launcher

import com.semiz.googletvremote.bluetooth.BluetoothHidService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 * Motor Nativo de Lanzamiento en 1 Toque (Sin librerías externas que rompan Gradle y SIN activar el asistente de voz):
 * 1. Si se configuró la IP del Google TV:
 *    - Intenta lanzar por DIAL HTTP REST (puerto 8008 de Google TV) o paquete ADB TCP (puerto 5555) con `monkey -p <package> 1`.
 * 2. Por Bluetooth HID puro:
 *    - Navega automáticamente al casillero (`homeSlotIndex`) de la fila "Tus aplicaciones" en la pantalla de inicio de Google TV
 *      usando `0x0223` (Home) + D-Pad + `0x0041` (OK), sin tocar jamás el micrófono de voz (`0x0221`).
 */
class TvAppLauncherEngine(
    private val btHidService: BluetoothHidService
) {
    companion object {
        val DEFAULT_TV_APPS = listOf(
            TvAppShortcut("netflix", "Netflix", "com.netflix.ninja", "Netflix", 0, 0xFFE50914),
            TvAppShortcut("dgo", "DGO", "com.directv.dtvlatam", "DGO", 1, 0xFF0284C7),
            TvAppShortcut("disney", "Disney+", "com.disney.disneyplus", "DisneyPlus", 2, 0xFF1E40AF),
            TvAppShortcut("prime", "Prime Video", "com.amazon.amazonvideo.livingroom", "AmazonInstantVideo", 3, 0xFF00A8E1),
            TvAppShortcut("xupertv", "XuperTV", "com.xuper.tv", "XuperTV", 4, 0xFF7C3AED),
            TvAppShortcut("youtube", "YouTube", "com.google.android.youtube.tv", "YouTube", 5, 0xFFDC2626)
        )
    }

    suspend fun launchAppOneTap(
        app: TvAppShortcut,
        tvIpAddress: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanIp = tvIpAddress?.trim().orEmpty()

        if (cleanIp.isNotEmpty()) {
            // 1A. Intentar DIAL HTTP en puerto 8008 (nativo en Chromecast / Google TV)
            if (app.dialAppName.isNotEmpty()) {
                try {
                    val url = URL("http://$cleanIp:8008/apps/${app.dialAppName}")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 900
                        readTimeout = 900
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write("") }
                    if (conn.responseCode in 200..299) {
                        return@withContext Result.success("🚀 ${app.name} abierto directo por red ($cleanIp)")
                    }
                } catch (_: Exception) {}
            }

            // 1B. Intentar ADB TCP directo en puerto 5555 (trama CNXN + OPEN shell:monkey)
            try {
                if (sendAdbShellCommand(cleanIp, "monkey -p ${app.packageName} -c android.intent.category.LEANBACK_LAUNCHER 1")) {
                    return@withContext Result.success("🚀 ${app.name} (${app.packageName}) lanzado por ADB")
                }
            } catch (_: Exception) {}
        }

        // 2. Lanzamiento por Bluetooth HID (Casillero de la fila 'Tus Apps' del Home de Google TV, SIN asistente de voz)
        btHidService.launchAppViaHomeGridSlot(app.homeSlotIndex)
        Result.success("🔵 BT HID: Abriendo ${app.name} (Casillero #${app.homeSlotIndex + 1} del Home)")
    }

    private fun sendAdbShellCommand(ip: String, command: String): Boolean {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, 5555), 900)
            socket.soTimeout = 1200
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()

            // Enviar paquete ADB CNXN
            val banner = "host::\u0000".toByteArray(Charsets.UTF_8)
            out.write(buildAdbPacket(0x4e584e43, 0x01000000, 4096, banner))
            out.flush()

            val header = ByteArray(24)
            if (inp.read(header) < 24) return false

            // Enviar paquete ADB OPEN con el comando shell
            val shellPayload = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
            out.write(buildAdbPacket(0x4e45504f, 1, 0, shellPayload))
            out.flush()
            return true
        }
    }

    private fun buildAdbPacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
        val sum = payload.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
        val buf = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(sum)
        buf.putInt(cmd xor -1)
        buf.put(payload)
        return buf.array()
    }
}
