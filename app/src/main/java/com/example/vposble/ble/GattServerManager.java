package com.example.vposble.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * GATT Server Manager for POS device
 * POS acts as a BLE Peripheral that customer phones can connect to
 */
public class GattServerManager {
    private static final String TAG = "GattServerManager";

    private Context context;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private Handler handler;

    private boolean isAdvertising = false;
    private Set<BluetoothDevice> connectedDevices = new HashSet<>();

    private GattServerCallback callback;

    public interface GattServerCallback {
        void onDeviceConnected(BluetoothDevice device);
        void onDeviceDisconnected(BluetoothDevice device);
        void onDataReceived(BluetoothDevice device, byte[] data);
        void onError(String message);
    }

    public GattServerManager(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());

        bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    public void setCallback(GattServerCallback callback) {
        this.callback = callback;
    }

    public boolean startServer() {
        if (bluetoothManager == null) {
            if (callback != null) {
                callback.onError("Bluetooth not supported");
            }
            return false;
        }

        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
        if (gattServer == null) {
            if (callback != null) {
                callback.onError("Failed to open GATT server");
            }
            return false;
        }

        // Create VPOS service
        BluetoothGattService service = new BluetoothGattService(
            BleManager.VPOS_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        // Create characteristic with read, write, and notify properties
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
            BleManager.VPOS_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ |
            BluetoothGattCharacteristic.PROPERTY_WRITE |
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ |
            BluetoothGattCharacteristic.PERMISSION_WRITE
        );

        service.addCharacteristic(characteristic);
        gattServer.addService(service);

        Log.d(TAG, "GATT server started");
        return true;
    }

    public void startAdvertising() {
        if (bluetoothAdapter == null) {
            if (callback != null) {
                callback.onError("Bluetooth adapter not available");
            }
            return;
        }

        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            if (callback != null) {
                callback.onError("BLE advertising not supported");
            }
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build();

        AdvertiseData data = new AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(new ParcelUuid(BleManager.VPOS_SERVICE_UUID))
            .build();

        advertiser.startAdvertising(settings, data, advertiseCallback);
        Log.d(TAG, "Started advertising");
    }

    public void stopAdvertising() {
        if (advertiser != null && isAdvertising) {
            advertiser.stopAdvertising(advertiseCallback);
            isAdvertising = false;
            Log.d(TAG, "Stopped advertising");
        }
    }

    public void stopServer() {
        stopAdvertising();

        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }

        connectedDevices.clear();
        Log.d(TAG, "GATT server stopped");
    }

    public boolean isAdvertising() {
        return isAdvertising;
    }

    public Set<BluetoothDevice> getConnectedDevices() {
        return new HashSet<>(connectedDevices);
    }

    public void sendData(BluetoothDevice device, byte[] data) {
        if (gattServer == null) {
            if (callback != null) {
                callback.onError("GATT server not running");
            }
            return;
        }

        BluetoothGattService service = gattServer.getService(BleManager.VPOS_SERVICE_UUID);
        if (service == null) {
            return;
        }

        BluetoothGattCharacteristic characteristic =
            service.getCharacteristic(BleManager.VPOS_CHARACTERISTIC_UUID);
        if (characteristic == null) {
            return;
        }

        characteristic.setValue(data);
        gattServer.notifyCharacteristicChanged(device, characteristic, false);
        Log.d(TAG, "Sent data to device: " + device.getAddress());
    }

    public void sendDataToAll(byte[] data) {
        for (BluetoothDevice device : connectedDevices) {
            sendData(device, data);
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            isAdvertising = true;
            Log.d(TAG, "Advertising started successfully");
        }

        @Override
        public void onStartFailure(int errorCode) {
            isAdvertising = false;
            Log.e(TAG, "Advertising failed: " + errorCode);
            if (callback != null) {
                callback.onError("Advertising failed: " + errorCode);
            }
        }
    };

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device);
                Log.d(TAG, "Device connected: " + device.getAddress());

                handler.post(() -> {
                    if (callback != null) {
                        callback.onDeviceConnected(device);
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device);
                Log.d(TAG, "Device disconnected: " + device.getAddress());

                handler.post(() -> {
                    if (callback != null) {
                        callback.onDeviceDisconnected(device);
                    }
                });
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                int offset, BluetoothGattCharacteristic characteristic) {
            if (BleManager.VPOS_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                gattServer.sendResponse(device, requestId,
                    android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0,
                    characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            if (BleManager.VPOS_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                characteristic.setValue(value);

                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId,
                        android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, null);
                }

                handler.post(() -> {
                    if (callback != null) {
                        callback.onDataReceived(device, value);
                    }
                });
            }
        }
    };
}
