package cx.aphex.energysign.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
import cx.aphex.energysign.MainViewModel
import cx.aphex.energysign.ext.logD
import cx.aphex.energysign.ext.logW
import io.reactivex.rxjava3.core.Observable

class EnergySignBluetoothManager(
    val context: Context,
    val viewModel: MainViewModel
) {

    val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter

    //TODO should not pass in viewmodel to gatt server
    private val energySignBluetoothGattServer: EnergySignBluetoothGattServer =
        EnergySignBluetoothGattServer(context, bluetoothManager, viewModel)

    val receivedBytes: Observable<ByteArray> = energySignBluetoothGattServer.receivedBytes

    init {
        energySignBluetoothGattServer.bluetoothStatusUpdates.subscribe {
            viewModel.onBtStatusUpdate(it)
        }
    }

    /**
     * Initialize the GATT server instance with the services/characteristics
     * from the service Profile.
     */
    private fun startServer() {
        energySignBluetoothGattServer.start()
    }

    /**
     * Shut down the GATT server.
     */
    private fun stopServer() {
        energySignBluetoothGattServer.stop()
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

    val BLE_PIN: String = "123456"

    /**
     * Listens for Bluetooth pairing requests and bond state changes
     */
    private val bondStateReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {

            val bluetoothDevice: BluetoothDevice =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            val type =
                intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
            // type appears to be 3 which is [PAIRING_VARIANT_CONSENT]

            when (intent.action) {
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    logD("Auto-entering pin: $BLE_PIN...")
                    val pinHasBeenSet = bluetoothDevice.setPin(BLE_PIN.toByteArray())
//                    abortBroadcast()
//                    val bondingWillBegin = bluetoothDevice.createBond()
//                    logD("Starting bond=$bondingWillBegin")
                    logD("PIN entered result: $pinHasBeenSet")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    when (bluetoothDevice.bondState) {
                        BluetoothDevice.BOND_BONDING -> {
                            logD("Bonding with remote device ${bluetoothDevice.address}...")
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            logD("BONDED with remote device ${bluetoothDevice.address}!")
                        }
                        BluetoothDevice.BOND_NONE -> {
                            logD("Remote device ${bluetoothDevice.address} is no longer bonded.")
                        }
                    }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    // MAC address
                    logD("Bluetooth device found!")
                    logD("Device Name: >${bluetoothDevice.name}<")
                    logD("deviceHardwareAddress >${bluetoothDevice.address}<")
                }
            }
        }
    }

    fun start() {
        // We can't continue without proper Bluetooth support
        if (!context.checkBluetoothSupport(bluetoothAdapter)) {
            logD("No bluetooth support, exiting")
            throw RuntimeException("No bluetooth support, exiting")
        }

        // Register for system Bluetooth events
        context.registerReceiver(
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )

        context.registerReceiver(bondStateReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_FOUND) //when new devices are discovered
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            })

        if (!bluetoothAdapter.isEnabled) {
            logD("Bluetooth is currently disabled...enabling")
            bluetoothAdapter.enable()
        } else {
            logD("Bluetooth enabled...starting services")
            startAdvertising()
            startServer()
        }
    }

    fun stop() {
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter.isEnabled) {
            stopServer()
            stopAdvertising()
        }

        context.unregisterReceiver(bluetoothReceiver)
    }

    /**
     * Callback to receive information about the advertisement process.
     */
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            logD("LE Advertise Started! Name: ${bluetoothManager.adapter.name}")
            viewModel.updateAdvertiseStatus("💚${bluetoothManager.adapter.name}") // 🏠${bluetoothManager.adapter.address}"
        }

        override fun onStartFailure(errorCode: Int) {
            logW("LE Advertise Failed: $errorCode")
            val error = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED ->
                    "ADVERTISE_FAILED_ALREADY_STARTED\n" +
                            "Failed to start advertising as the advertising is already started."
                ADVERTISE_FAILED_DATA_TOO_LARGE ->
                    "ADVERTISE_FAILED_DATA_TOO_LARGE\n" +
                            "Failed to start advertising as the advertise data to be broadcasted is larger than 31 bytes."
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                    "ADVERTISE_FAILED_FEATURE_UNSUPPORTED\n" +
                            "This feature is not supported on this platform."
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                    "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS\n" +
                            "Failed to start advertising because no advertising instance is available."
                ADVERTISE_FAILED_INTERNAL_ERROR ->
                    "ADVERTISE_FAILED_INTERNAL_ERROR\n" +
                            "Operation failed due to an internal error."
                else -> "Unknown Advertise Error, error code not part of the AdvertiseCallback enum "
            }
            logW(error)
            viewModel.updateAdvertiseStatus("💔$error")
        }
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
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(NordicUartServiceProfile.NORDIC_UART_SERVICE_UUID))
                .build()

            it.startAdvertising(settings, data, advertiseCallback)
        } ?: logW("Failed to create advertiser")
    }

    /**
     * Stop Bluetooth advertisements.
     */
    private fun stopAdvertising() {
        bluetoothManager.adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
    }

}

/**
 * Verify the level of Bluetooth support provided by the hardware.
 * @param bluetoothAdapter System [BluetoothAdapter].
 * @return true if Bluetooth is properly supported, false otherwise.
 */
fun Context.checkBluetoothSupport(bluetoothAdapter: BluetoothAdapter?): Boolean {

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
