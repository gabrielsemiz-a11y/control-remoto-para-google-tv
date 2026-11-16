package com.semiz.googletvremote.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BluetoothHidService(private val context: Context) {

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedTv: BluetoothDevice? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _connectionStatus = MutableStateFlow("Esperando conexión Bluetooth HID...")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices

    // Descriptor HID combinado: Report ID 2 (Consumer Control) + Report ID 1 (Keyboard)
    private val HID_REPORT_DESC = byteArrayOf(
        0x05.toByte(), 0x0C.toByte(),
        0x09.toByte(), 0x01.toByte(),
        0xA1.toByte(), 0x01.toByte(),
        0x85.toByte(), 0x02.toByte(),
        0x19.toByte(), 0x00.toByte(),
        0x2A.toByte(), 0x3C.toByte(), 0x02.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x26.toByte(), 0x3C.toByte(), 0x02.toByte(),
        0x95.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x10.toByte(),
        0x81.toByte(), 0x00.toByte(),
        0xC0.toByte(),
        0x05.toByte(), 0x01.toByte(),
        0x09.toByte(), 0x06.toByte(),
        0xA1.toByte(), 0x01.toByte(),
        0x85.toByte(), 0x01.toByte(),
        0x05.toByte(), 0x07.toByte(),
        0x19.toByte(), 0xE0.toByte(),
        0x29.toByte(), 0xE7.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x08.toByte(),
        0x81.toByte(), 0x02.toByte(),
        0x95.toByte(), 0x01.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x81.toByte(), 0x01.toByte(),
        0x95.toByte(), 0x06.toByte(),
        0x75.toByte(), 0x08.toByte(),
        0x15.toByte(), 0x00.toByte(),
        0x25.toByte(), 0x65.toByte(),
        0x05.toByte(), 0x07.toByte(),
        0x19.toByte(), 0x00.toByte(),
        0x29.toByte(), 0x65.toByte(),
        0x81.toByte(), 0x00.toByte(),
        0xC0.toByte()
    )

    fun initialize() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _connectionStatus.value = "Activá el Bluetooth del celular"
            return
        }
        refreshPairedDevices()
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    registerHidApp()
                }
            }
            override fun onServiceDisconnected(profile: Int) {
                hidDevice = null
                _connectionStatus.value = "Servicio Bluetooth HID desconectado"
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun refreshPairedDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        _pairedDevices.value = adapter.bondedDevices?.toList() ?: emptyList()
    }

    private fun registerHidApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Google TV Remote Pro",
            "Control Bluetooth HID + Lanzador Apps",
            "SemizLabs",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HID_REPORT_DESC
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX
        )
        hidDevice?.registerApp(sdp, null, qos, Executors.newCachedThreadPool(), object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                if (registered) {
                    _connectionStatus.value = "HID Listo · Tocá un TV vinculado para conectar"
                    pluggedDevice?.let { connectToTv(it) }
                }
            }
            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedTv = device
                    _connectionStatus.value = "Conectado a ${device.name ?: device.address}"
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    if (connectedTv?.address == device.address) connectedTv = null
                    _connectionStatus.value = "Desconectado de ${device.name ?: device.address}"
                }
            }
        })
    }

    fun connectToTv(device: BluetoothDevice) {
        hidDevice?.connect(device)
        _connectionStatus.value = "Conectando con ${device.name ?: device.address}..."
    }

    fun sendConsumerUsage(usageCode: Int) {
        val tv = connectedTv ?: return
        val lowByte = (usageCode and 0xFF).toByte()
        val highByte = ((usageCode shr 8) and 0xFF).toByte()
        hidDevice?.sendReport(tv, 2, byteArrayOf(lowByte, highByte))
        hidDevice?.sendReport(tv, 2, byteArrayOf(0x00, 0x00))
    }

    fun launchAppViaBluetoothMacro(appName: String) {
        scope.launch {
            sendConsumerUsage(0x0221) // AC Search en Google TV
            delay(320)
            for (ch in appName.lowercase()) {
                val hidKey = charToHidKeyCode(ch)
                if (hidKey != 0.toByte()) {
                    sendKeyboardKey(hidKey)
                    delay(45)
                }
            }
            delay(200)
            sendKeyboardKey(0x28) // Enter
        }
    }

    private fun sendKeyboardKey(keyCode: Byte) {
        val tv = connectedTv ?: return
        hidDevice?.sendReport(tv, 1, byteArrayOf(0, 0, keyCode, 0, 0, 0, 0, 0))
        hidDevice?.sendReport(tv, 1, ByteArray(8))
    }

    private fun charToHidKeyCode(c: Char): Byte = when (c) {
        in 'a'..'z' -> (c - 'a' + 4).toByte()
        ' ' -> 0x2C.toByte()
        else -> 0x00.toByte()
    }
}
