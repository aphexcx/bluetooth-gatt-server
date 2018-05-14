/*
 * Copyright 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.gattserver

import android.app.Activity
import android.bluetooth.*
import android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.text.format.DateFormat
import android.util.Log
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import com.google.android.things.pio.PeripheralManager
import com.google.android.things.pio.UartDevice
import com.google.android.things.pio.UartDeviceCallback
import java.io.IOException
import java.lang.RuntimeException
import java.util.*


private const val TAG = "GattServerActivity"

class GattServerActivity : Activity() {

    private var currentStringNeedsWrite: Boolean = true
    private var currentString: ByteArray = byteArrayOf()
        set(value) {
            field = value
            currentStringNeedsWrite = true
            stringView.post { stringView.text = STRING_PREFIX + String(value) }
        }

    /* Local UI */
    private lateinit var localTimeView: TextView
    private lateinit var stringView: TextView
    private lateinit var logList: ListView
    private lateinit var logAdapter: ArrayAdapter<String>
    private lateinit var advertiserStatusView: TextView

    private val logStrings: ArrayList<String> = arrayListOf()

    /* Bluetooth API */
    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothGattServer: BluetoothGattServer? = null
    /* Collection of notification subscribers */
    private val registeredDevices = mutableSetOf<BluetoothDevice>()

    private lateinit var uartDevice: UartDevice

    private val uartCallback: UartDeviceCallback = object : UartDeviceCallback {
        override fun onUartDeviceDataAvailable(uart: UartDevice): Boolean {
            logD("Data available on ${uart.name}! Reading...")
            // Read available data from the UART device
            try {
                val bytes = ByteArray(ARDUINO_STRING_LEN)
                val count = uart.read(bytes, bytes.size)
                logD("Read $count bytes from ${uart.name}: [${String(bytes)}]")
            } catch (e: IOException) {
                logW("Unable to read from UART device ${uart.name}: $e")
            }

            if (currentStringNeedsWrite) {
                logD("Writing currentString [${String(currentString)}] to ${uart.name}...")

                try {
                    uartDevice.write(currentString)
                    currentStringNeedsWrite = true //TODO change to false to not write every time
                } catch (e: Exception) {
                    logW("Unable to write to UART device ${uart.name}: $e")
                }
            }
            // Continue listening for more interrupts
            return true
        }

        override fun onUartDeviceError(uart: UartDevice, error: Int) {
            logW("$uart: Error event $error")
        }

    }

    override fun onStart() {
        super.onStart()
        logD("Uart device $UART_DEVICE_NAME callback registered")
        uartDevice.registerUartDeviceCallback(uartCallback)
    }

    override fun onStop() {
        super.onStop()
        logD("Uart device $UART_DEVICE_NAME callback unregistered")
        uartDevice.unregisterUartDeviceCallback(uartCallback)

    }

    /**
     * Listens for Bluetooth adapter events to enable/disable
     * advertising and server functionality.
     */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
            logD("bluetoothReceiver: received intent: $state")
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    logD("bluetoothReceiver: STATE_ON")
                    startAdvertising()
                    startServer()
                }
                BluetoothAdapter.STATE_OFF -> {
                    logD("bluetoothReceiver: STATE_OFF")
                    stopServer()
                    stopAdvertising()
                }
            }
        }
    }

    /**
     * Callback to receive information about the advertisement process.
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            logD("LE Advertise Started! Name: ${bluetoothManager.adapter.name}")
            advertiserStatusView.text = "💚${bluetoothManager.adapter.name}"
        }

        override fun onStartFailure(errorCode: Int) {
            logW("LE Advertise Failed: $errorCode")
            val error = when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
                    "ADVERTISE_FAILED_ALREADY_STARTED\n" +
                            "Failed to start advertising as the advertising is already started."
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
                    "ADVERTISE_FAILED_DATA_TOO_LARGE\n" +
                            "Failed to start advertising as the advertise data to be broadcasted is larger than 31 bytes."
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                    "ADVERTISE_FAILED_FEATURE_UNSUPPORTED\n" +
                            "This feature is not supported on this platform."
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                    "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS\n" +
                            "Failed to start advertising because no advertising instance is available."
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
                    "ADVERTISE_FAILED_INTERNAL_ERROR\n" +
                            "Operation failed due to an internal error."
                else -> "Unknown Advertise Error, error code not part of the AdvertiseCallback enum "
            }
            logW(error)
            advertiserStatusView.text = "💔$error"
        }
    }

    /**
     * Callback to handle incoming requests to the GATT server.
     * All read/write requests for characteristics and descriptors are handled here.
     */
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            logD("MTU Changed! New MTU: $mtu")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                STATE_CONNECTED -> logD("BluetoothDevice CONNECTED: $device")
                STATE_DISCONNECTED -> {
                    logD("BluetoothDevice DISCONNECTED: $device")
                    //Remove device from any active subscriptions
                    registeredDevices.remove(device)
                }
            }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice?,
                                                  requestId: Int,
                                                  characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean,
                                                  responseNeeded: Boolean,
                                                  offset: Int,
                                                  value: ByteArray?) {
            when (characteristic.uuid) {
                TimeProfile.CHARACTERISTIC_INTERACTOR_UUID -> {
                    logD("Write Interactor String! Value: [${String(value!!)}]")
                    currentString = value
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            currentString)
                }
                else -> {
                    // Invalid characteristic
                    logW("Invalid Characteristic Read: " + characteristic.uuid)
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null)
                }
            }

        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {
            val now = System.currentTimeMillis()
            when (characteristic.uuid) {
                TimeProfile.CURRENT_TIME -> {
                    logD("Read CurrentTime")
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            TimeProfile.getExactTime(now, TimeProfile.ADJUST_NONE))
                }
                TimeProfile.LOCAL_TIME_INFO -> {
                    logD("Read LocalTimeInfo")
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            TimeProfile.getLocalTimeInfo(now))
                }
                TimeProfile.CHARACTERISTIC_READER_UUID -> {
                    logD("Read String")
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            currentString)
                }
                TimeProfile.CHARACTERISTIC_INTERACTOR_UUID -> {
                    logD("Read Interactor String???")
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            currentString)
                }
                else -> {
                    // Invalid characteristic
                    logW("Invalid Characteristic Read: " + characteristic.uuid)
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null)
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                             descriptor: BluetoothGattDescriptor) {
            if (descriptor.uuid == TimeProfile.CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR) {
                logD("Config descriptor read")
                val returnValue = if (registeredDevices.contains(device)) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                bluetoothGattServer?.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        returnValue)
            } else {
                logW("Unknown descriptor read request")
                bluetoothGattServer?.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0, null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice,
                                              requestId: Int,
                                              descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean,
                                              responseNeeded: Boolean,
                                              offset: Int,
                                              value: ByteArray) {
            if (descriptor.uuid == TimeProfile.CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR) {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    logD("Subscribe device to notifications: $device")
                    registeredDevices.add(device)
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                    logD("Unsubscribe device from notifications: $device")
                    registeredDevices.remove(device)
                }

                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0, null)
                }
            } else {
                logW("Unknown descriptor write request")
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0, null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)

        timeFormat = DateFormat.getTimeFormat(this)
        dateFormat = DateFormat.getMediumDateFormat(this)

        localTimeView = findViewById(R.id.text_time)
        stringView = findViewById(R.id.current_string)
        advertiserStatusView = findViewById(R.id.advertiser_status)
        logList = findViewById(R.id.log_list)

        currentString = "TRONCE".toByteArray()

        logAdapter = ArrayAdapter(this, R.layout.item_log, logStrings)
        logList.adapter = logAdapter

        // create Uart device

        // Devices with a display should not go to sleep
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        // We can't continue without proper Bluetooth support
        if (!checkBluetoothSupport(bluetoothAdapter)) {
            logD("No bluetooth support, exiting")
            fatal()
        }

        // UART
        uartDevice = configureUart() ?: fatal()
        logD("UART device $UART_DEVICE_NAME opened and configured!")

        // Register for system Bluetooth events
        val filter = IntentFilter(ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)
        if (!bluetoothAdapter.isEnabled) {
            logD("Bluetooth is currently disabled...enabling")
            bluetoothAdapter.enable()
        } else {
            logD("Bluetooth enabled...starting services")
            startAdvertising()
            startServer()
        }
    }

    private fun fatal(): Nothing {
        finish()
        throw RuntimeException("Fatal")
    }

    fun configureUart(): UartDevice? {
        try {
            val manager = PeripheralManager.getInstance()
            val deviceList = manager.uartDeviceList
            if (deviceList.isEmpty()) {
                logD("No UART port available on this device.")
            } else {
                logD("List of available UART devices: $deviceList")
            }
            logD("Opening UART device $UART_DEVICE_NAME")
            val uart = manager.openUartDevice(UART_DEVICE_NAME)
            logD("Configuring UART device $UART_DEVICE_NAME")
            // Configure the UART port
            uart.setBaudrate(UART_BAUD_RATE)
            uart.setDataSize(8)
            uart.setParity(UartDevice.PARITY_NONE)
            uart.setStopBits(1)
            return uart
        } catch (e: IOException) {
            logW("Unable to open/configure UART device: $e")
        }
        return null
    }

    fun UartDevice.write(buffer: ByteArray) {
        val finalBuffer = buffer + '\r'.toByte()
        val count = uartDevice.write(finalBuffer, finalBuffer.size)
        logD("Wrote $count bytes to ${uartDevice.name}")
    }

    private fun logD(s: String) {
        Log.d(TAG, s)
        addToLog(s)
    }

    private fun logW(s: String) {
        Log.w(TAG, s)
        addToLog("🚨W🚨 $s")
    }

    private fun addToLog(s: String) {
        //TODO do this on an interval, not whenever there's a log line, but this is fine for now
        updateTimeView(System.currentTimeMillis())
        val time = timeFormat.format(Date())
        logList.post {
            if (logAdapter.count > 101) {
                logAdapter.remove(logAdapter.getItem(0))
            }
            logAdapter.add("$time $s")
            logList.smoothScrollToPosition(logAdapter.count)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter.isEnabled) {
            stopServer()
            stopAdvertising()
        }

        try {
            uartDevice.close()
        } catch (e: IOException) {
            Log.w(TAG, "Unable to close UART device", e)
        }

        unregisterReceiver(bluetoothReceiver)
    }

    /**
     * Verify the level of Bluetooth support provided by the hardware.
     * @param bluetoothAdapter System [BluetoothAdapter].
     * @return true if Bluetooth is properly supported, false otherwise.
     */
    private fun checkBluetoothSupport(bluetoothAdapter: BluetoothAdapter?): Boolean {

        if (bluetoothAdapter == null) {
            logW("Bluetooth is not supported")
            return false
        }

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            logW("Bluetooth LE is not supported")
            return false
        }

        return true
    }

    /**
     * Begin advertising over Bluetooth that this device is connectable
     * and supports the Current Time Service.
     */
    private fun startAdvertising() {
        bluetoothManager.adapter.name = "G!"
        bluetoothManager.adapter.bluetoothLeAdvertiser?.let {
            logD("Bluetooth LE: Start Advertiser")
            val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .build()

            val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .setIncludeTxPowerLevel(false)
//                    .addServiceUuid(ParcelUuid(TimeProfile.TIME_SERVICE))
                    .addServiceUuid(ParcelUuid(TimeProfile.STRING_SERVICE_UUID))
                    .build()

            it.startAdvertising(settings, data, advertiseCallback)
        } ?: logW("Failed to create advertiser")
    }

    /**
     * Stop Bluetooth advertisements.
     */
    private fun stopAdvertising() {
        bluetoothManager.adapter.bluetoothLeAdvertiser?.let {
            it.stopAdvertising(advertiseCallback)
        } ?: logW("Failed to create advertiser")
    }

    /**
     * Initialize the GATT server instance with the services/characteristics
     * from the Time Profile.
     */
    private fun startServer() {
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)

        bluetoothGattServer
                ?.addService(TimeProfile.createStringService())
                ?: logW("Unable to create GATT server")

        // Initialize the local UI
        updateTimeView(System.currentTimeMillis())
    }

    /**
     * Shut down the GATT server.
     */
    private fun stopServer() {
        bluetoothGattServer?.close()
    }

    private lateinit var timeFormat: java.text.DateFormat
    private lateinit var dateFormat: java.text.DateFormat

    /**
     * Update graphical UI on devices that support it with the current time.
     */
    private fun updateTimeView(timeMillis: Long) {
        val date = Date(timeMillis)
        val displayDate = dateFormat.format(date)
        val displayTime = timeFormat.format(date)
        localTimeView.text = "$displayDate 🕰 $displayTime"
    }

    companion object {
        private const val STRING_PREFIX: String = "🔠"
        private const val UART_DEVICE_NAME: String = "UART6"
        private const val UART_BAUD_RATE: Int = 19200
        private const val ARDUINO_STRING_LEN: Int = 512
    }
}
