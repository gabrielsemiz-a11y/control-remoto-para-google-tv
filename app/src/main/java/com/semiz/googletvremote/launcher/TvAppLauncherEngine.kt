package com.semiz.googletvremote.launcher

import com.semiz.googletvremote.bluetooth.BluetoothHidService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

data class TvAppShortcut(
    val id: String,
    val name: String,
    val packageName: String,
    val deepLink: String = "",
    val colorHex: Long,
    val pinned: Boolean = true
)

class TvAppLauncherEngine(
    private val btHidService: BluetoothHidService
) {
    companion object {
        val DEFAULT_TV_APPS = listOf(
            TvAppShortcut("netflix", "Netflix", "com.netflix.ninja", "netflix://", 0xFFE50914),
            TvAppShortcut("dgo", "DGO", "com.directv.dtvlatam", "dgo://live", 0xFF0284C7),
            TvAppShortcut("disney", "Disney+", "com.disney.disneyplus", "https://www.disneyplus.com", 0xFF1E40AF),
            TvAppShortcut("prime", "Prime Video", "com.amazon.amazonvideo.livingroom", "https://app.primevideo.com", 0xFF00A8E1),
            TvAppShortcut("xupertv", "XuperTV", "com.xuper.tv", "xupertv://home", 0xFF7C3AED),
            TvAppShortcut("youtube", "YouTube", "com.google.android.youtube.tv", "vnd.youtube.launch://", 0xFFDC2626)
        )
    }

    suspend fun launchAppOneTap(
        app: TvAppShortcut,
        tvIpAddress: String?,
        preferDirectIntent: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (preferDirectIntent && !tvIpAddress.isNullOrBlank()) {
                val cmd = "monkey -p ${app.packageName} -c android.intent.category.LEANBACK_LAUNCHER 1"
                sendDirectTvCommand(tvIpAddress, cmd)
                Result.success("Lanzado directo por Package: ${app.packageName}")
            } else {
                btHidService.launchAppViaBluetoothMacro(app.name)
                Result.success("Lanzado por Macro Bluetooth HID: ${app.name}")
            }
        } catch (e: Exception) {
            btHidService.launchAppViaBluetoothMacro(app.name)
            Result.success("Fallback Bluetooth HID ejecutado para ${app.name}")
        }
    }

    private fun sendDirectTvCommand(ip: String, shellCommand: String) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, 5555), 800)
            OutputStreamWriter(socket.getOutputStream()).use { writer ->
                writer.write("shell:$shellCommand\n")
                writer.flush()
            }
        }
    }
}
