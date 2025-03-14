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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class BluetoothAudioModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    private val bluetoothManager: BluetoothManager = reactContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val audioManager: AudioManager = reactContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectedDevices = mutableListOf<BluetoothDevice>()
    private var audioRecord: AudioRecord? = null
    private val audioTracks = mutableMapOf<String, AudioTrack>()
    private var isVirtualSinkActive = AtomicBoolean(false)
    private var isAudioCapturing = AtomicBoolean(false)
    private var audioThread: Thread? = null
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private var discoveryReceiver: BroadcastReceiver? = null

    override fun getName(): String = "BluetoothAudioModule"

    @ReactMethod
    fun startVirtualSink(promise: Promise) {
        try {
            if (bluetoothAdapter == null) {
                promise.reject("ERROR", "Bluetooth not supported")
                return
            }

            if (isVirtualSinkActive.get()) {
                promise.resolve(true)
                return
            }

            // Create a virtual audio sink
            try {
                // Initialize the audio system for output
                val outputAudioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(BUFFER_SIZE)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                outputAudioTrack.play()
                println("DEBUG: Virtual sink audio track created and started")
                
                // Store this as the main output track
                synchronized(audioTracks) {
                    audioTracks["main_output"] = outputAudioTrack
                }
                
                isVirtualSinkActive.set(true)
                println("DEBUG: Virtual sink activated successfully")
                promise.resolve(true)
            } catch (e: Exception) {
                println("DEBUG: Failed to create virtual sink: ${e.message}")
                promise.reject("ERROR", "Failed to create virtual sink: ${e.message}")
            }
        } catch (e: Exception) {
            println("DEBUG: Virtual sink startup error: ${e.message}")
            promise.reject("ERROR", "Failed to start virtual sink: ${e.message}")
        }
    }

    @ReactMethod
    fun startAudioCapture(promise: Promise) {
        try {
            if (!isVirtualSinkActive.get()) {
                promise.reject("ERROR", "Virtual sink must be started first")
                return
            }

            if (isAudioCapturing.get()) {
                promise.resolve(true)
                return
            }

            // Initialize audio capture
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.REMOTE_SUBMIX,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            // Start audio processing thread
            audioThread = thread(start = true) {
                val buffer = ShortArray(BUFFER_SIZE)
                audioRecord?.startRecording()
                isAudioCapturing.set(true)

                while (isAudioCapturing.get()) {
                    val readResult = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: 0
                    if (readResult > 0) {
                        // Broadcast to all connected devices
                        synchronized(audioTracks) {
                            audioTracks.values.forEach { track ->
                                try {
                                    track.write(buffer, 0, readResult)
                                } catch (e: Exception) {
                                    println("Error writing to audio track: ${e.message}")
                                }
                            }
                        }
                    }
                }
            }

            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", "Failed to start audio capture: ${e.message}")
        }
    }

    @ReactMethod
    fun stopAudioCapture(promise: Promise) {
        try {
            isAudioCapturing.set(false)
            audioThread?.join(1000) // Wait for thread to finish
            
            audioRecord?.apply {
                stop()
                release()
            }
            audioRecord = null

            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("ERROR", "Failed to stop audio capture: ${e.message}")
        }
    }

    @ReactMethod
    fun stopVirtualSink(promise: Promise) {
        try {
            // First stop audio capture if it's running
            if (isAudioCapturing.get()) {
                stopAudioCapture(promise)
            }

            isVirtualSinkActive.set(false)

            synchronized(audioTracks) {
                audioTracks.values.forEach { track ->
                    try {
                        track.stop()
                        track.release()
                    } catch (e: Exception) {
                        // Ignore cleanup errors
                    }
                }
                audioTracks.clear()
            }

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
                if (!isVirtualSinkActive.get()) {
                    promise.reject("ERROR", "Virtual sink not active")
                    return
                }

                // Create audio track for this device
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(BUFFER_SIZE)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack.play()

                synchronized(audioTracks) {
                    audioTracks[deviceAddress] = audioTrack
                }

                // Connect using A2DP
                bluetoothAdapter?.getProfileProxy(reactApplicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            try {
                                val a2dp = proxy as BluetoothA2dp
                                
                                // Set priority and connect
                                val setPriorityMethod = a2dp.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.java)
                                setPriorityMethod.invoke(a2dp, device, 100)
                                
                                val connectMethod = a2dp.javaClass.getMethod("connect", BluetoothDevice::class.java)
                                val result = connectMethod.invoke(a2dp, device) as Boolean
                                
                                if (result) {
                                    connectedDevices.add(device)
                                    sendEvent("deviceConnected", device.address)
                                    promise.resolve(true)
                                } else {
                                    // Cleanup on failure
                                    synchronized(audioTracks) {
                                        audioTracks.remove(deviceAddress)?.release()
                                    }
                                    promise.reject("ERROR", "Failed to connect A2DP profile")
                                }
                            } catch (e: Exception) {
                                // Cleanup on failure
                                synchronized(audioTracks) {
                                    audioTracks.remove(deviceAddress)?.release()
                                }
                                promise.reject("ERROR", "Failed to connect: ${e.message}")
                            }
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP) {
                            synchronized(audioTracks) {
                                audioTracks.remove(deviceAddress)?.release()
                            }
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
            synchronized(audioTracks) {
                audioTracks.remove(deviceAddress)?.apply {
                    stop()
                    release()
                }
            }
            
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
            println("DEBUG: Virtual Sync connecting to ${total} devices")
            
            if (total == 0) {
                promise.reject("ERROR", "No device addresses provided")
                return
            }

            // First ensure our virtual sink is active
            if (!isVirtualSinkActive.get()) {
                startVirtualSink(object : Promise {
                    override fun resolve(value: Any?) {
                        // Once virtual sink is started, proceed with connections
                        connectDevicesWithActiveSync(addresses, promise)
                    }

                    override fun reject(code: String) {
                        promise.reject(code)
                    }

                    override fun reject(code: String?, message: String?) {
                        promise.reject(code, message)
                    }

                    override fun reject(throwable: Throwable) {
                        promise.reject(throwable)
                    }

                    override fun reject(code: String?, throwable: Throwable?) {
                        promise.reject(code, throwable)
                    }

                    override fun reject(code: String?, message: String?, throwable: Throwable?) {
                        promise.reject(code, message, throwable)
                    }

                    override fun reject(throwable: Throwable, map: WritableMap) {
                        promise.reject(throwable, map)
                    }

                    override fun reject(code: String, map: WritableMap) {
                        promise.reject(code, map)
                    }

                    override fun reject(code: String, throwable: Throwable, map: WritableMap) {
                        promise.reject(code, throwable, map)
                    }

                    override fun reject(code: String, message: String, map: WritableMap) {
                        promise.reject(code, message, map)
                    }

                    override fun reject(code: String, message: String, throwable: Throwable, map: WritableMap) {
                        promise.reject(code, message, throwable, map)
                    }
                })
            } else {
                connectDevicesWithActiveSync(addresses, promise)
            }

        } catch (e: Exception) {
            println("DEBUG: Error: ${e.message}")
            promise.reject("ERROR", e.message)
        }
    }

    private fun connectDevicesWithActiveSync(addresses: ReadableArray, promise: Promise) {
        val total = addresses.size()
        val results = Arguments.createArray()
        var processedCount = 0
        var successCount = 0

        println("DEBUG: Starting connections with active Virtual Sync")

        // Process each device
        addresses.toArrayList().mapNotNull { addr ->
            addr as? String
        }.forEach { deviceAddress ->
            try {
                val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
                if (device == null) {
                    val result = Arguments.createMap().apply {
                        putString("id", deviceAddress)
                        putString("status", "device not found")
                    }
                    results.pushMap(result)
                    processedCount++
                    checkCompletion(processedCount, total, successCount, results, promise)
                    return@forEach
                }

                println("DEBUG: Connecting to device: ${device.name} (${device.address})")

                // Create audio track for this device
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(BUFFER_SIZE)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                // Connect using A2DP
                bluetoothAdapter?.getProfileProxy(reactApplicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            try {
                                val a2dp = proxy as BluetoothA2dp

                                // Ensure device is bonded
                                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                                    device.createBond()
                                    var attempts = 0
                                    while (device.bondState != BluetoothDevice.BOND_BONDED && attempts < 10) {
                                        Thread.sleep(500)
                                        attempts++
                                    }
                                }

                                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                                    // Set priority and connect
                                    val setPriorityMethod = a2dp.javaClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.java)
                                    setPriorityMethod.invoke(a2dp, device, 100)
                                    
                                    val connectMethod = a2dp.javaClass.getMethod("connect", BluetoothDevice::class.java)
                                    val result = connectMethod.invoke(a2dp, device) as Boolean

                                    if (result) {
                                        println("DEBUG: Successfully connected to ${device.name}")
                                        audioTrack.play()
                                        synchronized(audioTracks) {
                                            audioTracks[deviceAddress] = audioTrack
                                        }
                                        connectedDevices.add(device)
                                        sendEvent("deviceConnected", device.address)
                                        
                                        val resultMap = Arguments.createMap().apply {
                                            putString("id", deviceAddress)
                                            putString("status", "connected")
                                        }
                                        results.pushMap(resultMap)
                                        successCount++
                                    } else {
                                        audioTrack.release()
                                        val resultMap = Arguments.createMap().apply {
                                            putString("id", deviceAddress)
                                            putString("status", "connection failed")
                                        }
                                        results.pushMap(resultMap)
                                    }
                                } else {
                                    audioTrack.release()
                                    val resultMap = Arguments.createMap().apply {
                                        putString("id", deviceAddress)
                                        putString("status", "bonding failed")
                                    }
                                    results.pushMap(resultMap)
                                }
                            } catch (e: Exception) {
                                audioTrack.release()
                                val resultMap = Arguments.createMap().apply {
                                    putString("id", deviceAddress)
                                    putString("status", "error: ${e.message}")
                                }
                                results.pushMap(resultMap)
                            } finally {
                                processedCount++
                                checkCompletion(processedCount, total, successCount, results, promise)
                            }
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP) {
                            synchronized(audioTracks) {
                                audioTracks.remove(deviceAddress)?.release()
                            }
                            connectedDevices.remove(device)
                            sendEvent("deviceDisconnected", device.address)
                        }
                    }
                }, BluetoothProfile.A2DP)

            } catch (e: Exception) {
                val resultMap = Arguments.createMap().apply {
                    putString("id", deviceAddress)
                    putString("status", "error: ${e.message}")
                }
                results.pushMap(resultMap)
                processedCount++
                checkCompletion(processedCount, total, successCount, results, promise)
            }
        }
    }

    private fun checkCompletion(processed: Int, total: Int, successes: Int, results: WritableArray, promise: Promise) {
        if (processed == total) {
            if (successes > 0) {
                // Convert results array to a map for the error case
                val resultMap = Arguments.createMap()
                resultMap.putArray("results", results)
                if (successes < total) {
                    promise.reject("PARTIAL_SUCCESS", "Some devices failed to connect", null, resultMap)
                } else {
                    promise.resolve(results)
                }
            } else {
                val resultMap = Arguments.createMap()
                resultMap.putArray("results", results)
                promise.reject("ERROR", "Failed to connect to any devices", null, resultMap)
            }
        }
    }

    private fun formatConnectCommand(deviceAddress: String): ByteArray {
        // Implement this according to your sync device's protocol
        // For example, it might be something like:
        // return "CONNECT $deviceAddress\n".toByteArray()
        return ByteArray(0) // Placeholder
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