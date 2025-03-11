package com.yourcompany.bluetoothification

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.*

class BluetoothAudioModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    private val bluetoothManager: BluetoothManager = reactContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val audioManager: AudioManager = reactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectedDevices = mutableListOf<BluetoothDevice>()
    private var audioTrack: AudioTrack? = null
    private var isVirtualSinkActive = false
    private var discoveryReceiver: BroadcastReceiver? = null

    override fun getName(): String = "BluetoothAudioModule"

    @ReactMethod
    fun startVirtualSink(promise: Promise) {
        try {
            if (bluetoothAdapter == null) {
                promise.reject("ERROR", "Bluetooth not supported")
                return
            }

            // Create a virtual A2DP sink profile
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SPP UUID
            
            // Initialize audio playback
            val bufferSize = AudioTrack.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isVirtualSinkActive = true
            
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopVirtualSink(promise: Promise) {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            isVirtualSinkActive = false
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun createBond(deviceAddress: String, promise: Promise) {
        try {
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            device?.let {
                if (it.bondState == BluetoothDevice.BOND_BONDED) {
                    promise.resolve(true)
                    return
                }
                
                // Register a receiver to listen for bond state changes
                val bondStateReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                            val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                            val bondDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            
                            if (bondDevice?.address == deviceAddress) {
                                when (bondState) {
                                    BluetoothDevice.BOND_BONDED -> {
                                        reactApplicationContext.unregisterReceiver(this)
                                        promise.resolve(true)
                                    }
                                    BluetoothDevice.BOND_NONE -> {
                                        reactApplicationContext.unregisterReceiver(this)
                                        promise.reject("ERROR", "Bonding failed")
                                    }
                                }
                            }
                        }
                    }
                }
                
                val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                reactApplicationContext.registerReceiver(bondStateReceiver, filter)
                
                // Initiate bonding
                if (it.createBond()) {
                    // Bonding initiated successfully, result will be handled by the broadcast receiver
                } else {
                    reactApplicationContext.unregisterReceiver(bondStateReceiver)
                    promise.reject("ERROR", "Failed to initiate bonding")
                }
            } ?: promise.reject("ERROR", "Device not found")
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun connectDevice(deviceAddress: String, promise: Promise) {
        try {
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            device?.let {
                if (!isVirtualSinkActive) {
                    promise.reject("ERROR", "Virtual sink not active")
                    return
                }

                // Get the A2DP proxy
                bluetoothAdapter?.getProfileProxy(reactApplicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            try {
                                val a2dp = proxy as BluetoothA2dp
                                
                                // Check if already connected
                                if (a2dp.getConnectedDevices().contains(device)) {
                                    if (!connectedDevices.contains(device)) {
                                        connectedDevices.add(device)
                                        sendEvent("deviceConnected", device.address)
                                    }
                                    promise.resolve(true)
                                    return
                                }

                                // Connect to the A2DP profile
                                // Note: This requires reflection as setPriority and connect are hidden APIs
                                try {
                                    val setPriorityMethod = a2dp.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.java)
                                    setPriorityMethod.invoke(a2dp, device, 100) // BluetoothProfile.PRIORITY_ON

                                    val connectMethod = a2dp.javaClass.getMethod("connect", BluetoothDevice::class.java)
                                    val result = connectMethod.invoke(a2dp, device) as Boolean
                                    
                                    if (result) {
                                        connectedDevices.add(device)
                                        sendEvent("deviceConnected", device.address)
                                        promise.resolve(true)
                                    } else {
                                        promise.reject("ERROR", "Failed to connect A2DP profile")
                                    }
                                } catch (e: Exception) {
                                    promise.reject("ERROR", "Failed to connect: ${e.message}")
                                }
                            } catch (e: Exception) {
                                promise.reject("ERROR", "A2DP connection error: ${e.message}")
                            }
                        } else {
                            promise.reject("ERROR", "Unexpected profile type")
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP) {
                            connectedDevices.remove(device)
                            sendEvent("deviceDisconnected", device.address)
                        }
                    }
                }, BluetoothProfile.A2DP)
            } ?: promise.reject("ERROR", "Device not found")
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun disconnectDevice(deviceAddress: String, promise: Promise) {
        try {
            val device = connectedDevices.find { it.address == deviceAddress }
            device?.let {
                connectedDevices.remove(it)
                sendEvent("deviceDisconnected", deviceAddress)
                promise.resolve(true)
            } ?: promise.reject("ERROR", "Device not found")
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun getBondedDevices(promise: Promise) {
        try {
            val bondedDevices = bluetoothAdapter?.bondedDevices
            val devicesArray = Arguments.createArray()
            bondedDevices?.forEach { device ->
                // Optionally, you can filter by device class (e.g., audio devices) using device.bluetoothClass,
                // but for now we'll include all bonded devices
                val map = Arguments.createMap()
                map.putString("name", device.name ?: "Unknown")
                map.putString("id", device.address)
                // You can add additional info like type if needed
                devicesArray.pushMap(map)
            }
            promise.resolve(devicesArray)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun connectMultipleDevices(addresses: ReadableArray, promise: Promise) {
        try {
            val total = addresses.size()
            if (total == 0) {
                promise.reject("ERROR", "No device addresses provided")
                return
            }
            val results = Arguments.createArray()
            var processedCount = 0
            for (i in 0 until total) {
                val addr = addresses.getString(i) ?: ""
                val device = bluetoothAdapter?.getRemoteDevice(addr)
                if (device == null) {
                    val map = Arguments.createMap()
                    map.putString("id", addr)
                    map.putString("status", "device not found")
                    results.pushMap(map)
                    processedCount++
                    if (processedCount == total) {
                        promise.resolve(results)
                    }
                    continue
                }
                bluetoothAdapter?.getProfileProxy(reactApplicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            if (!connectedDevices.contains(device)) {
                                connectedDevices.add(device)
                                sendEvent("deviceConnected", device.address)
                            }
                            val map = Arguments.createMap()
                            map.putString("id", device.address)
                            map.putString("status", "connected")
                            results.pushMap(map)
                        } else {
                            val map = Arguments.createMap()
                            map.putString("id", device.address)
                            map.putString("status", "unexpected profile")
                            results.pushMap(map)
                        }
                        processedCount++
                        if (processedCount == total) {
                            promise.resolve(results)
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        // handle disconnection if needed
                        val map = Arguments.createMap()
                        map.putString("id", device.address)
                        map.putString("status", "disconnected")
                        results.pushMap(map)
                        processedCount++
                        if (processedCount == total) {
                            promise.resolve(results)
                        }
                    }
                }, BluetoothProfile.A2DP)
            }
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    private fun sendEvent(eventName: String, params: Any?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for React Native event emitter
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for React Native event emitter
    }

    @ReactMethod
    fun startClassicDiscovery(promise: Promise) {
        try {
            if (bluetoothAdapter == null) {
                promise.reject("ERROR", "Bluetooth adapter not available")
                return
            }
            
            // Cancel ongoing discovery if any
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            
            val filter = IntentFilter()
            filter.addAction(BluetoothDevice.ACTION_FOUND)
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            
            discoveryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            device?.let {
                                // Filter for A2DP capable devices using major device class AUDIO_VIDEO
                                val btClass = it.bluetoothClass
                                if (btClass != null && btClass.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO) {
                                    val deviceInfo = Arguments.createMap().apply {
                                        putString("id", it.address)
                                        putString("name", it.name ?: "Unknown Device")
                                        putInt("rssi", intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt())
                                    }
                                    sendEvent("classicDeviceFoundDetailed", deviceInfo)
                                }
                            }
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            sendEvent("classicDiscoveryFinished", null)
                            // Unregister the receiver
                            try {
                                reactApplicationContext.unregisterReceiver(this)
                            } catch (e: Exception) {
                                // Already unregistered
                            }
                            discoveryReceiver = null
                        }
                    }
                }
            }
            
            reactApplicationContext.registerReceiver(discoveryReceiver, filter)
            
            val started = bluetoothAdapter.startDiscovery()
            if (!started) {
                promise.reject("ERROR", "Failed to start discovery")
            } else {
                promise.resolve(true)
            }
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }

    @ReactMethod
    fun stopClassicDiscovery(promise: Promise) {
        try {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            discoveryReceiver?.let {
                try {
                    reactApplicationContext.unregisterReceiver(it)
                } catch (e: Exception) {
                    // Already unregistered, ignore
                }
                discoveryReceiver = null
            }
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }
} 