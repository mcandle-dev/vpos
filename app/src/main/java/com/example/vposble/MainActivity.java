package com.example.vposble;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vposble.ble.BleManager;
import com.example.vposble.ble.GattServerManager;
import com.example.vposble.ui.DeviceAdapter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_ENABLE_BT = 1;
    private static final int REQUEST_PERMISSIONS = 2;

    private BleManager bleManager;
    private GattServerManager gattServerManager;

    private Button btnScan;
    private Button btnStartServer;
    private Button btnSend;
    private EditText etMessage;
    private TextView tvStatus;
    private TextView tvLog;
    private RecyclerView rvDevices;

    private DeviceAdapter deviceAdapter;
    private List<BluetoothDevice> deviceList = new ArrayList<>();

    private StringBuilder logBuilder = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initBle();
        setupListeners();
    }

    private void initViews() {
        btnScan = findViewById(R.id.btn_scan);
        btnStartServer = findViewById(R.id.btn_start_server);
        btnSend = findViewById(R.id.btn_send);
        etMessage = findViewById(R.id.et_message);
        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        rvDevices = findViewById(R.id.rv_devices);

        // Setup RecyclerView
        deviceAdapter = new DeviceAdapter(deviceList, device -> {
            // Connect to selected device
            bleManager.connect(device);
            appendLog("Connecting to: " + getDeviceName(device));
        });

        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        rvDevices.setAdapter(deviceAdapter);
    }

    private void initBle() {
        bleManager = new BleManager(this);
        gattServerManager = new GattServerManager(this);

        // Setup BLE Manager callback
        bleManager.setCallback(new BleManager.BleCallback() {
            @Override
            public void onDeviceFound(BluetoothDevice device, int rssi) {
                runOnUiThread(() -> {
                    if (!deviceList.contains(device)) {
                        deviceList.add(device);
                        deviceAdapter.notifyItemInserted(deviceList.size() - 1);
                        appendLog("Found: " + getDeviceName(device) + " (RSSI: " + rssi + ")");
                    }
                });
            }

            @Override
            public void onConnectionStateChanged(boolean connected) {
                runOnUiThread(() -> {
                    if (connected) {
                        tvStatus.setText("Status: Connected");
                        appendLog("Connected to device");
                    } else {
                        tvStatus.setText("Status: Disconnected");
                        appendLog("Disconnected from device");
                    }
                });
            }

            @Override
            public void onDataReceived(byte[] data) {
                runOnUiThread(() -> {
                    String message = new String(data, StandardCharsets.UTF_8);
                    appendLog("Received: " + message);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    appendLog("Error: " + message);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }
        });

        // Setup GATT Server callback
        gattServerManager.setCallback(new GattServerManager.GattServerCallback() {
            @Override
            public void onDeviceConnected(BluetoothDevice device) {
                runOnUiThread(() -> {
                    tvStatus.setText("Status: Client Connected");
                    appendLog("Client connected: " + getDeviceName(device));
                });
            }

            @Override
            public void onDeviceDisconnected(BluetoothDevice device) {
                runOnUiThread(() -> {
                    tvStatus.setText("Status: Server Running");
                    appendLog("Client disconnected: " + getDeviceName(device));
                });
            }

            @Override
            public void onDataReceived(BluetoothDevice device, byte[] data) {
                runOnUiThread(() -> {
                    String message = new String(data, StandardCharsets.UTF_8);
                    appendLog("From " + getDeviceName(device) + ": " + message);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    appendLog("Server Error: " + message);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void setupListeners() {
        btnScan.setOnClickListener(v -> {
            if (checkPermissions()) {
                if (bleManager.isScanning()) {
                    bleManager.stopScan();
                    btnScan.setText("Scan Devices");
                } else {
                    deviceList.clear();
                    deviceAdapter.notifyDataSetChanged();
                    bleManager.startScan();
                    btnScan.setText("Stop Scan");
                    appendLog("Scanning for devices...");
                }
            }
        });

        btnStartServer.setOnClickListener(v -> {
            if (checkPermissions()) {
                if (gattServerManager.isAdvertising()) {
                    gattServerManager.stopServer();
                    btnStartServer.setText("Start Server");
                    tvStatus.setText("Status: Server Stopped");
                    appendLog("Server stopped");
                } else {
                    if (gattServerManager.startServer()) {
                        gattServerManager.startAdvertising();
                        btnStartServer.setText("Stop Server");
                        tvStatus.setText("Status: Server Running");
                        appendLog("Server started, advertising...");
                    }
                }
            }
        });

        btnSend.setOnClickListener(v -> {
            String message = etMessage.getText().toString().trim();
            if (message.isEmpty()) {
                Toast.makeText(this, "Enter a message", Toast.LENGTH_SHORT).show();
                return;
            }

            byte[] data = message.getBytes(StandardCharsets.UTF_8);

            // Send via client connection
            bleManager.sendData(data);

            // Send via server to all connected clients
            gattServerManager.sendDataToAll(data);

            appendLog("Sent: " + message);
            etMessage.setText("");
        });
    }

    private boolean checkPermissions() {
        List<String> permissions = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
        } else {
            // Android 11 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissions.toArray(new String[0]),
                    REQUEST_PERMISSIONS);
            return false;
        }

        // Check if Bluetooth is enabled
        if (!bleManager.isBluetoothEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            return false;
        }

        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                Toast.makeText(this, "Permissions required for BLE", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode != RESULT_OK) {
                Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void appendLog(String message) {
        logBuilder.append(message).append("\n");
        tvLog.setText(logBuilder.toString());

        // Auto-scroll to bottom
        // You may need to add ScrollView around tvLog
    }

    private String getDeviceName(BluetoothDevice device) {
        String name = device.getName();
        if (name == null || name.isEmpty()) {
            return device.getAddress();
        }
        return name;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bleManager.close();
        gattServerManager.stopServer();
    }
}
