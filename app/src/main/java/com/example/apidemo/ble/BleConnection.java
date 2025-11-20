package com.example.apidemo.ble;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vpos.apipackage.At;

/**
 * BLE Connection Manager using AT commands
 * Handles connection, data transmission, and channel configuration
 */
public class BleConnection {
    private static final String TAG = "BleConnection";

    private Integer connectionHandle = null;
    private boolean testMode = false;

    // Result classes
    public static class ConnectionResult {
        private final boolean success;
        private final String error;
        private final Integer handle;

        public ConnectionResult(boolean success, Integer handle, String error) {
            this.success = success;
            this.handle = handle;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }
        public Integer getHandle() { return handle; }
    }

    public static class SendResult {
        private final boolean success;
        private final String error;

        public SendResult(boolean success, String error) {
            this.success = success;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }
    }

    public static class ReceiveResult {
        private final boolean success;
        private final byte[] data;
        private final String error;
        private final boolean timeout;

        public ReceiveResult(boolean success, byte[] data, String error, boolean timeout) {
            this.success = success;
            this.data = data;
            this.error = error;
            this.timeout = timeout;
        }

        public boolean isSuccess() { return success; }
        public byte[] getData() { return data; }
        public String getError() { return error; }
        public boolean isTimeout() { return timeout; }
    }

    public static class UuidChannel {
        public final int channelNum;
        public final String uuid;
        public final String properties;

        public UuidChannel(int channelNum, String uuid, String properties) {
            this.channelNum = channelNum;
            this.uuid = uuid;
            this.properties = properties;
        }
    }

    public static class UuidScanResult {
        private final boolean success;
        private final List<UuidChannel> channels;
        private final String error;

        public UuidScanResult(boolean success, List<UuidChannel> channels, String error) {
            this.success = success;
            this.channels = channels;
            this.error = error;
        }

        public boolean isSuccess() { return success; }
        public List<UuidChannel> getChannels() { return channels; }
        public String getError() { return error; }
    }

    public BleConnection() {
        this.testMode = false;
    }

    public BleConnection(boolean testMode) {
        this.testMode = testMode;
    }

    /**
     * Connect to a BLE device
     * @param macAddress MAC address in format XX:XX:XX:XX:XX:XX
     * @return ConnectionResult with handle on success
     */
    public ConnectionResult connectToDevice(String macAddress) {
        Log.d(TAG, "Connecting to device: " + macAddress);

        // Send AT+CONNECT command
        String cmd = "AT+CONNECT=," + macAddress + "\r\n";
        int ret = At.Lib_AtSendData(cmd.getBytes(), cmd.length());

        if (ret != 0) {
            Log.e(TAG, "Failed to send connect command, ret: " + ret);
            return new ConnectionResult(false, null, "Failed to send command: " + ret);
        }

        // Receive response
        byte[] response = new byte[512];
        int[] len = new int[1];
        ret = At.Lib_ComRecvAT(response, len, 20, 5000);

        if (ret != 0 || len[0] == 0) {
            Log.e(TAG, "Failed to receive connect response, ret: " + ret);
            return new ConnectionResult(false, null, "No response from device");
        }

        String responseStr = new String(response, 0, len[0]);
        Log.d(TAG, "Connect response: " + responseStr);

        // Parse response: "OK\r\n[MAC] CONNECTED [handle]"
        Integer handle = parseConnectResponse(responseStr);
        if (handle != null) {
            connectionHandle = handle;
            Log.d(TAG, "Connected with handle: " + handle);
            return new ConnectionResult(true, handle, null);
        } else {
            Log.e(TAG, "Failed to parse connect response");
            return new ConnectionResult(false, null, "Failed to parse response: " + responseStr);
        }
    }

    /**
     * Disconnect from the connected device
     * @return true if disconnected successfully
     */
    public boolean disconnect() {
        if (connectionHandle == null) {
            Log.w(TAG, "No active connection to disconnect");
            return false;
        }

        Log.d(TAG, "Disconnecting handle: " + connectionHandle);

        // Send AT+DISCE command
        String cmd = "AT+DISCE=" + connectionHandle + "\r\n";
        int ret = At.Lib_AtSendData(cmd.getBytes(), cmd.length());

        if (ret != 0) {
            Log.e(TAG, "Failed to send disconnect command, ret: " + ret);
            return false;
        }

        // Receive response
        byte[] response = new byte[256];
        int[] len = new int[1];
        ret = At.Lib_ComRecvAT(response, len, 20, 3000);

        String responseStr = new String(response, 0, len[0]);
        Log.d(TAG, "Disconnect response: " + responseStr);

        connectionHandle = null;
        return responseStr.contains("OK");
    }

    /**
     * Scan for UUID channels on the connected device
     * @return UuidScanResult with list of available channels
     */
    public UuidScanResult scanUuidChannels() {
        if (connectionHandle == null) {
            return new UuidScanResult(false, null, "Not connected");
        }

        Log.d(TAG, "Scanning UUID channels");

        // Send AT+UUID_SCAN command
        String cmd = "AT+UUID_SCAN=1\r\n";
        int ret = At.Lib_AtSendData(cmd.getBytes(), cmd.length());

        if (ret != 0) {
            return new UuidScanResult(false, null, "Failed to send command: " + ret);
        }

        // Receive response
        byte[] response = new byte[2048];
        int[] len = new int[1];
        ret = At.Lib_ComRecvAT(response, len, 20, 5000);

        if (ret != 0 || len[0] == 0) {
            return new UuidScanResult(false, null, "No response");
        }

        String responseStr = new String(response, 0, len[0]);
        Log.d(TAG, "UUID scan response: " + responseStr);

        // Parse response: "-CHAR:[num] UUID:[uuid],[properties];"
        List<UuidChannel> channels = parseUuidScanResponse(responseStr);
        return new UuidScanResult(true, channels, null);
    }

    /**
     * Set TRX channel for data communication
     * @param writeCh Write channel number
     * @param notifyCh Notify channel number
     * @param type Write type (0=without response, 1=with response)
     * @return true if set successfully
     */
    public boolean setTrxChannel(int writeCh, int notifyCh, int type) {
        if (connectionHandle == null) {
            Log.e(TAG, "Cannot set TRX channel: not connected");
            return false;
        }

        Log.d(TAG, String.format("Setting TRX channel: write=%d, notify=%d, type=%d",
            writeCh, notifyCh, type));

        // Send AT+TRX_CHAN command
        String cmd = String.format("AT+TRX_CHAN=%d,%d,%d,%d\r\n",
            connectionHandle, writeCh, notifyCh, type);
        int ret = At.Lib_AtSendData(cmd.getBytes(), cmd.length());

        if (ret != 0) {
            Log.e(TAG, "Failed to send TRX channel command, ret: " + ret);
            return false;
        }

        // Receive response
        byte[] response = new byte[256];
        int[] len = new int[1];
        ret = At.Lib_ComRecvAT(response, len, 20, 3000);

        String responseStr = new String(response, 0, len[0]);
        Log.d(TAG, "TRX channel response: " + responseStr);

        return responseStr.contains("OK");
    }

    /**
     * Send data to the connected device
     * @param data Data to send
     * @param timeout Timeout in milliseconds
     * @return SendResult
     */
    public SendResult sendData(byte[] data, int timeout) {
        if (connectionHandle == null) {
            return new SendResult(false, "Not connected");
        }

        Log.d(TAG, "Sending data, size: " + data.length);

        // Send AT+SEND command
        String cmd = String.format("AT+SEND=%d,%d,%d\r\n",
            connectionHandle, data.length, timeout);
        int ret = At.Lib_AtSendData(cmd.getBytes(), cmd.length());

        if (ret != 0) {
            return new SendResult(false, "Failed to send command: " + ret);
        }

        // Wait for "INPUT_BLE_DATA:" prompt
        byte[] response = new byte[256];
        int[] len = new int[1];
        ret = At.Lib_ComRecvAT(response, len, 20, 1000);

        String responseStr = new String(response, 0, len[0]);
        Log.d(TAG, "Send prompt response: " + responseStr);

        if (!responseStr.contains("INPUT_BLE_DATA") && !responseStr.contains("OK")) {
            return new SendResult(false, "Unexpected response: " + responseStr);
        }

        // Send actual data
        ret = At.Lib_AtSendData(data, data.length);

        if (ret != 0) {
            return new SendResult(false, "Failed to send data: " + ret);
        }

        // Wait for confirmation
        ret = At.Lib_ComRecvAT(response, len, 20, timeout);
        responseStr = new String(response, 0, len[0]);
        Log.d(TAG, "Send data response: " + responseStr);

        if (responseStr.contains("OK") || responseStr.contains("SEND_OK")) {
            return new SendResult(true, null);
        } else {
            return new SendResult(false, "Send failed: " + responseStr);
        }
    }

    /**
     * Receive data from the connected device
     * @param timeout Timeout in milliseconds
     * @return ReceiveResult with received data
     */
    public ReceiveResult receiveData(int timeout) {
        if (connectionHandle == null) {
            return new ReceiveResult(false, null, "Not connected", false);
        }

        byte[] response = new byte[2048];
        int[] len = new int[1];
        int ret = At.Lib_ComRecvAT(response, len, 20, timeout);

        if (ret != 0) {
            return new ReceiveResult(false, null, "Receive error: " + ret, false);
        }

        if (len[0] == 0) {
            return new ReceiveResult(false, null, "Timeout", true);
        }

        String responseStr = new String(response, 0, len[0]);
        Log.d(TAG, "Received data: " + responseStr);

        // Parse received data
        byte[] data = parseReceivedData(responseStr);
        if (data != null) {
            return new ReceiveResult(true, data, null, false);
        } else {
            // Return raw response if can't parse
            return new ReceiveResult(true, response, null, false);
        }
    }

    /**
     * Check if connected to a device
     * @return true if connected
     */
    public boolean isConnected() {
        return connectionHandle != null;
    }

    /**
     * Get the current connection handle
     * @return connection handle or null if not connected
     */
    public Integer getConnectionHandle() {
        return connectionHandle;
    }

    // Parse connect response to extract handle
    private Integer parseConnectResponse(String response) {
        // Pattern: "[MAC] CONNECTED [handle]" or "CONNECTED [handle]"
        Pattern pattern = Pattern.compile("CONNECTED\\s+(\\d+)");
        Matcher matcher = pattern.matcher(response);

        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                Log.e(TAG, "Failed to parse handle: " + e.getMessage());
            }
        }
        return null;
    }

    // Parse UUID scan response
    private List<UuidChannel> parseUuidScanResponse(String response) {
        List<UuidChannel> channels = new ArrayList<>();

        // Pattern: "-CHAR:[num] UUID:[uuid],[properties];"
        Pattern pattern = Pattern.compile("-CHAR:(\\d+)\\s+UUID:([^,]+),([^;]+);");
        Matcher matcher = pattern.matcher(response);

        while (matcher.find()) {
            try {
                int channelNum = Integer.parseInt(matcher.group(1));
                String uuid = matcher.group(2).trim();
                String properties = matcher.group(3).trim();
                channels.add(new UuidChannel(channelNum, uuid, properties));
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse UUID channel: " + e.getMessage());
            }
        }

        return channels;
    }

    // Parse received data from response
    private byte[] parseReceivedData(String response) {
        // Look for data pattern in response
        // This may need adjustment based on actual response format
        Pattern pattern = Pattern.compile("DATA:([0-9A-Fa-f]+)");
        Matcher matcher = pattern.matcher(response);

        if (matcher.find()) {
            String hexData = matcher.group(1);
            return hexStringToByteArray(hexData);
        }

        return null;
    }

    // Convert hex string to byte array
    private byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
