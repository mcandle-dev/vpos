package com.example.vposble.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BleManager {
    private static final String TAG = "BleManager";

    // VPOS BLE Service UUID
    public static final UUID VPOS_SERVICE_UUID =
        UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    public static final UUID VPOS_CHARACTERISTIC_UUID =
        UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");

    private Context context;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt bluetoothGatt;
    private Handler handler;

    private boolean isScanning = false;
    private static final long SCAN_PERIOD = 10000; // 10 seconds

    private BleCallback callback;

    public interface BleCallback {
        void onDeviceFound(BluetoothDevice device, int rssi);
        void onConnectionStateChanged(boolean connected);
        void onDataReceived(byte[] data);
        void onError(String message);
    }

    public BleManager(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());

        bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }

    public void setCallback(BleCallback callback) {
        this.callback = callback;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isBleSupported() {
        return context.getPackageManager().hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE);
    }

    public void startScan() {
        if (!isBluetoothEnabled()) {
            if (callback != null) {
                callback.onError("Bluetooth is not enabled");
            }
            return;
        }

        if (isScanning) {
            return;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            if (callback != null) {
                callback.onError("BLE Scanner not available");
            }
            return;
        }

        // Stop scanning after SCAN_PERIOD
        handler.postDelayed(this::stopScan, SCAN_PERIOD);

        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();

        List<ScanFilter> filters = new ArrayList<>();
        // Optionally filter by service UUID
        // filters.add(new ScanFilter.Builder()
        //     .setServiceUuid(new ParcelUuid(VPOS_SERVICE_UUID))
        //     .build());

        isScanning = true;
        bleScanner.startScan(filters, settings, scanCallback);
        Log.d(TAG, "BLE scan started");
    }

    public void stopScan() {
        if (!isScanning || bleScanner == null) {
            return;
        }

        isScanning = false;
        bleScanner.stopScan(scanCallback);
        Log.d(TAG, "BLE scan stopped");
    }

    public boolean isScanning() {
        return isScanning;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            int rssi = result.getRssi();

            if (callback != null) {
                callback.onDeviceFound(device, rssi);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "Scan failed with error: " + errorCode);
            isScanning = false;
            if (callback != null) {
                callback.onError("Scan failed: " + errorCode);
            }
        }
    };

    public void connect(BluetoothDevice device) {
        if (device == null) {
            if (callback != null) {
                callback.onError("Device is null");
            }
            return;
        }

        // Stop scanning before connecting
        stopScan();

        // Close existing connection
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }

        bluetoothGatt = device.connectGatt(context, false, gattCallback);
        Log.d(TAG, "Connecting to device: " + device.getAddress());
    }

    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
        }
    }

    public void close() {
        stopScan();
        if (bluetoothGatt != null) {
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    public void sendData(byte[] data) {
        if (bluetoothGatt == null) {
            if (callback != null) {
                callback.onError("Not connected");
            }
            return;
        }

        BluetoothGattService service = bluetoothGatt.getService(VPOS_SERVICE_UUID);
        if (service == null) {
            if (callback != null) {
                callback.onError("VPOS service not found");
            }
            return;
        }

        BluetoothGattCharacteristic characteristic =
            service.getCharacteristic(VPOS_CHARACTERISTIC_UUID);
        if (characteristic == null) {
            if (callback != null) {
                callback.onError("VPOS characteristic not found");
            }
            return;
        }

        characteristic.setValue(data);
        boolean success = bluetoothGatt.writeCharacteristic(characteristic);
        Log.d(TAG, "Write characteristic: " + success);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server");
                gatt.discoverServices();

                handler.post(() -> {
                    if (callback != null) {
                        callback.onConnectionStateChanged(true);
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server");

                handler.post(() -> {
                    if (callback != null) {
                        callback.onConnectionStateChanged(false);
                    }
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered");

                // Enable notifications for VPOS characteristic
                BluetoothGattService service = gatt.getService(VPOS_SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic characteristic =
                        service.getCharacteristic(VPOS_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true);
                    }
                }
            } else {
                Log.e(TAG, "Service discovery failed: " + status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                byte[] data = characteristic.getValue();
                handler.post(() -> {
                    if (callback != null) {
                        callback.onDataReceived(data);
                    }
                });
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            handler.post(() -> {
                if (callback != null) {
                    callback.onDataReceived(data);
                }
            });
        }
    };
}
