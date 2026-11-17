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
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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
                    _connectionStatus.value = "HID Listo · Elegí tu Google TV en 'Conectar Bluetooth'"
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
     * Navegación por Grillado de Home de Google TV (SIN activar el micrófono de Google Assistant):
     * 1. Envía AC Home (0x0223) para ir al inicio.
     * 2. Baja a la fila de 'Tus aplicaciones' y se desplaza hasta la posición (gridIndex) configurada.
     * 3. Envía Enter (0x0041) para abrir la app directamente.
     */
    fun launchAppViaHomeGridSlot(slotIndex: Int) {
        scope.launch {
            // 1. Ir al Home de Google TV
            sendConsumerUsage(0x0223)
            delay(650)
            // 2. Bajar a la fila de aplicaciones favoritas
            sendConsumerUsage(0x0043) // DPAD_DOWN
            delay(260)
            sendConsumerUsage(0x0043) // DPAD_DOWN (fila Tus Apps en Google TV)
            delay(260)
            // Ir al primer ícono a la izquierda para tener referencia fija
            repeat(8) {
                sendConsumerUsage(0x0044) // DPAD_LEFT
                delay(70)
            }
            delay(150)
            // Mover a la derecha según el número de casillero (0 = primera app, 1 = segunda, etc.)
            repeat(slotIndex.coerceAtLeast(0)) {
                sendConsumerUsage(0x0045) // DPAD_RIGHT
                delay(140)
            }
            delay(180)
            // 3. Abrir la aplicación (OK / Menu Pick)
            sendConsumerUsage(0x0041)
        }
    }
}
