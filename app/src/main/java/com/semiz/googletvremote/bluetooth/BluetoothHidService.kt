package com.semiz.googletvremote.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
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
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _connectionStatus = MutableStateFlow("Desconectado · Tocá 'Conectar Bluetooth'")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _connectedDevice = MutableStateFlow<BluetoothDevice?>(null)
    val connectedDevice: StateFlow<BluetoothDevice?> = _connectedDevice

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

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

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (dev != null && !dev.name.isNullOrBlank()) {
                        val current = _discoveredDevices.value
                        if (current.none { it.address == dev.address }) {
                            _discoveredDevices.value = current + dev
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    @Suppress("DEPRECATION")
                    val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (bondState == BluetoothDevice.BOND_BONDED && dev != null) {
                        refreshPairedDevices()
                        connectToTv(dev)
                    }
                }
            }
        }
    }

    fun initialize() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            _connectionStatus.value = "Bluetooth apagado · Tocá para activar"
            return
        }
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            context.registerReceiver(btReceiver, filter)
        } catch (_: Exception) {}

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
                _connectedDevice.value = null
                _connectionStatus.value = "Servicio Bluetooth HID desconectado"
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    fun refreshPairedDevices() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        _pairedDevices.value = adapter.bondedDevices?.toList() ?: emptyList()
    }

    fun startDiscoveryScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        refreshPairedDevices()
        _discoveredDevices.value = emptyList()
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        _isScanning.value = true
        adapter.startDiscovery()
    }

    fun pairOrConnectDevice(device: BluetoothDevice) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            _connectionStatus.value = "Vinculando con ${device.name ?: device.address}..."
            device.createBond()
        } else {
            connectToTv(device)
        }
    }

    fun openSystemBluetoothSettings() {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
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
                    _connectionStatus.value = "HID Listo · Tocá 'Conectar Bluetooth' para elegir tu TV"
                    pluggedDevice?.let { connectToTv(it) }
                }
            }
            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        _connectedDevice.value = device
                        _connectionStatus.value = "Conectado por BT a ${device.name ?: device.address}"
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        _connectionStatus.value = "Conectando a ${device.name ?: device.address}..."
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (_connectedDevice.value?.address == device.address) {
                            _connectedDevice.value = null
                        }
                        _connectionStatus.value = "Desconectado de ${device.name ?: device.address}"
                    }
                }
            }
        })
    }

    fun connectToTv(device: BluetoothDevice) {
        hidDevice?.connect(device)
        _connectionStatus.value = "Conectando con ${device.name ?: device.address}..."
    }

    fun disconnectCurrentTv() {
        val tv = _connectedDevice.value ?: return
        hidDevice?.disconnect(tv)
        _connectedDevice.value = null
        _connectionStatus.value = "Desconectado"
    }

    fun sendConsumerUsage(usageCode: Int) {
        val tv = _connectedDevice.value ?: return
        val lowByte = (usageCode and 0xFF).toByte()
        val highByte = ((usageCode shr 8) and 0xFF).toByte()
        hidDevice?.sendReport(tv, 2, byteArrayOf(lowByte, highByte))
        hidDevice?.sendReport(tv, 2, byteArrayOf(0x00, 0x00))
    }

    /**
     * Lanza cualquier app desde la fila 'Tus aplicaciones' del Home de Google TV vía Bluetooth HID
     * SIN activar jamás el micrófono de Google Assistant (no usa 0x0221).
     */
    fun launchAppViaHomeGridSlot(slotIndex: Int) {
        scope.launch {
            // 1. Ir al Home de Google TV (0x0223 AC Home)
            sendConsumerUsage(0x0223)
            delay(650)
            // 2. Bajar a la fila 'Tus aplicaciones'
            sendConsumerUsage(0x0043) // DPAD_DOWN
            delay(250)
            sendConsumerUsage(0x0043) // DPAD_DOWN
            delay(250)
            // 3. Alinear al primer casillero a la izquierda
            repeat(8) {
                sendConsumerUsage(0x0044) // DPAD_LEFT
                delay(65)
            }
            delay(140)
            // 4. Desplazar hasta el casillero de la app elegida (0 = 1ra app, 1 = 2da, etc.)
            repeat(slotIndex.coerceAtLeast(0)) {
                sendConsumerUsage(0x0045) // DPAD_RIGHT
                delay(130)
            }
            delay(170)
            // 5. Seleccionar (OK = 0x0041)
            sendConsumerUsage(0x0041)
        }
    }
}
