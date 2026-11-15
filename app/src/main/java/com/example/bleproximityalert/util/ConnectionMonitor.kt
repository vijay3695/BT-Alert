package com.example.bleproximityalert.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.bleproximityalert.model.ConnectedAudioDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConnectionMonitor(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _connectedDevices = MutableStateFlow<List<ConnectedAudioDevice>>(emptyList())
    val connectedDevices: StateFlow<List<ConnectedAudioDevice>> = _connectedDevices.asStateFlow()

    private val _onDeviceDisconnected = MutableStateFlow<String?>(null)
    val onDeviceDisconnected: StateFlow<String?> = _onDeviceDisconnected.asStateFlow()

    private var a2dpProfile: BluetoothA2dp? = null
    private var headsetProfile: BluetoothHeadset? = null
    private var isReceiverRegistered = false

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var pollJob: Job? = null
    private val disconnectGraceJobs = mutableMapOf<String, Job>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = getDeviceFromIntent(intent)

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    device?.let { cancelGracePeriod(it.address) }
                    refreshConnectedDevices()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> {
                    device?.let { scheduleDisconnectGracePeriod(it) }
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        device?.let { cancelGracePeriod(it.address) }
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        device?.let { scheduleDisconnectGracePeriod(it) }
                    }
                    refreshConnectedDevices()
                }
            }
        }
    }

    init {
        initProfiles()
        registerReceiver()
        startFallbackPoll()
    }

    private fun initProfiles() {
        try {
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProfile = proxy as? BluetoothA2dp
                    } else if (profile == BluetoothProfile.HEADSET) {
                        headsetProfile = proxy as? BluetoothHeadset
                    }
                    refreshConnectedDevices()
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) a2dpProfile = null
                    if (profile == BluetoothProfile.HEADSET) headsetProfile = null
                }
            }, BluetoothProfile.A2DP)

            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProfile = proxy as? BluetoothHeadset
                    }
                    refreshConnectedDevices()
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) headsetProfile = null
                }
            }, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing profiles", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshConnectedDevices() {
        if (!PermissionHelper.hasRequiredBluetoothPermissions(context)) return

        val deviceMap = mutableMapOf<String, ConnectedAudioDevice>()
        val currentAudioOutputs = getAudioOutputAddresses()

        // 1. Bonded paired devices
        try {
            val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
            for (dev in bonded) {
                val addr = dev.address
                val name = try { dev.name } catch (e: Exception) { null } ?: "Bluetooth Device"
                val isOutput = currentAudioOutputs.contains(addr)
                val isA2dp = a2dpProfile?.connectedDevices?.any { it.address == addr } == true
                val isHfp = headsetProfile?.connectedDevices?.any { it.address == addr } == true

                if (isOutput || isA2dp || isHfp) {
                    deviceMap[addr] = ConnectedAudioDevice(
                        name = name,
                        address = addr,
                        supportsA2dp = isA2dp,
                        supportsHeadset = isHfp,
                        isCurrentAudioOutput = isOutput
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying bonded devices", e)
        }

        // 2. A2DP Profile connected devices
        try {
            a2dpProfile?.connectedDevices?.forEach { dev ->
                val addr = dev.address
                val name = try { dev.name } catch (e: Exception) { null } ?: "A2DP Earphones"
                val isOutput = currentAudioOutputs.contains(addr)
                val existing = deviceMap[addr]
                deviceMap[addr] = ConnectedAudioDevice(
                    name = existing?.name ?: name,
                    address = addr,
                    supportsA2dp = true,
                    supportsHeadset = existing?.supportsHeadset ?: false,
                    isCurrentAudioOutput = isOutput
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying A2DP profile", e)
        }

        // 3. Headset Profile connected devices
        try {
            headsetProfile?.connectedDevices?.forEach { dev ->
                val addr = dev.address
                val name = try { dev.name } catch (e: Exception) { null } ?: "Headset"
                val isOutput = currentAudioOutputs.contains(addr)
                val existing = deviceMap[addr]
                deviceMap[addr] = ConnectedAudioDevice(
                    name = existing?.name ?: name,
                    address = addr,
                    supportsA2dp = existing?.supportsA2dp ?: false,
                    supportsHeadset = true,
                    isCurrentAudioOutput = isOutput
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Headset profile", e)
        }

        _connectedDevices.value = deviceMap.values.toList().sortedWith(
            compareByDescending<ConnectedAudioDevice> { it.isCurrentAudioOutput }
                .thenBy { it.name }
        )
    }

    private fun getAudioOutputAddresses(): Set<String> {
        val addresses = mutableSetOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager != null) {
                val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (info in outputs) {
                    if (info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.address != null) {
                            addresses.add(info.address)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading audio output devices", e)
        }
        return addresses
    }

    @SuppressLint("MissingPermission")
    fun isDeviceConnected(address: String): Boolean {
        if (_connectedDevices.value.any { it.address.equals(address, ignoreCase = true) }) return true
        return try {
            val a2dpConn = a2dpProfile?.connectedDevices?.any { it.address.equals(address, ignoreCase = true) } == true
            val hfpConn = headsetProfile?.connectedDevices?.any { it.address.equals(address, ignoreCase = true) } == true
            a2dpConn || hfpConn
        } catch (e: Exception) {
            false
        }
    }

    private fun scheduleDisconnectGracePeriod(device: BluetoothDevice) {
        val address = device.address
        cancelGracePeriod(address)
        disconnectGraceJobs[address] = coroutineScope.launch {
            delay(5000L) // 5-second grace period to avoid profile state glitches
            refreshConnectedDevices()
            if (!isDeviceConnected(address)) {
                Log.d(TAG, "Confirmed disconnection for $address after 5s grace period")
                _onDeviceDisconnected.value = address
            }
        }
    }

    private fun cancelGracePeriod(address: String) {
        disconnectGraceJobs.remove(address)?.cancel()
    }

    private fun startFallbackPoll() {
        pollJob?.cancel()
        pollJob = coroutineScope.launch {
            while (true) {
                delay(10000L) // 10-second fallback poll
                refreshConnectedDevices()
            }
        }
    }

    private fun registerReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            context.registerReceiver(receiver, filter)
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register connection receiver", e)
        }
    }

    fun unregister() {
        pollJob?.cancel()
        disconnectGraceJobs.values.forEach { it.cancel() }
        disconnectGraceJobs.clear()
        if (isReceiverRegistered) {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
            isReceiverRegistered = false
        }
        try {
            a2dpProfile?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProfile?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        } catch (e: Exception) {}
    }

    private fun getDeviceFromIntent(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    companion object {
        const val TAG = "ConnectionMonitor"
    }
}
