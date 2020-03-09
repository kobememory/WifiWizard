/*
 * Copyright 2015 Matt Parsons
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.pylonproducts.wifiwizard;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.BroadcastReceiver;
import android.content.IntentFilter;

import java.util.List;

import static android.content.Context.LOCATION_SERVICE;


public class WifiWizard extends CordovaPlugin {

    private static final String SCAN = "scan";
    private static final String ADD_NETWORK = "addNetwork";
    private static final String REMOVE_NETWORK = "removeNetwork";
    private static final String CONNECT_NETWORK = "connectNetwork";
    private static final String DISCONNECT_NETWORK = "disconnectNetwork";
    private static final String DISCONNECT = "disconnect";
    private static final String LIST_NETWORKS = "listNetworks";
    private static final String START_SCAN = "startScan";
    private static final String GET_SCAN_RESULTS = "getScanResults";
    private static final String GET_CONNECTED_SSID = "getConnectedSSID";
    private static final String GET_CONNECTED_SSID_WITHPERMISSION = "getConnectedSSIDWithpermission";
    private static final String OPEN_LOCATION_SERVICE = "openLocationService";
    private static final String IS_WIFI_ENABLED = "isWifiEnabled";
    private static final String SET_WIFI_ENABLED = "setWifiEnabled";
    private static final String IS_5GHZ = "is5g";
    private static final String TAG = "WifiWizard";

    private WifiManager wifiManager;
    private CallbackContext callbackContext;

    private CallbackContext getSSIDCallback;
    private CallbackContext startScanCallback;

    private CallbackContext openLocationServiceCallback;

    private static final int SCAN_RESULTS_CODE = 0; // Permissions request code for getScanResults()
    private final int PERMISSION_REQUEST_CODE = 1;
    private final int PERMISSION_REQUEST_CODE_GET_WIFI_LIST = 2;
    private static final String ACCESS_FINE_LOCATION = android.Manifest.permission.ACCESS_FINE_LOCATION;

    private int scanWifiListCount = 0;

    private LocationManager mLocationManager;

    String[] perms = {Manifest.permission.ACCESS_FINE_LOCATION};

    private final ContentObserver mGpsMonitor = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            boolean enabled = mLocationManager
                    .isProviderEnabled(LocationManager.GPS_PROVIDER);

            if (enabled) {
                getConnectedSSIDWithpermission();
            }
            Log.e("gps ： ", enabled + " ");
        }
    };

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        this.wifiManager = (WifiManager) cordova.getActivity().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.mLocationManager = (LocationManager) cordova.getActivity().getApplicationContext().getSystemService(LOCATION_SERVICE);
        cordova.getActivity().getApplicationContext().getContentResolver()
                .registerContentObserver(
                        Settings.Secure
                                .getUriFor(Settings.Secure.LOCATION_PROVIDERS_ALLOWED),
                        false, mGpsMonitor);
    }

    @Override
    public boolean execute(String action, final JSONArray data, final CallbackContext callbackContext)
            throws JSONException {
        this.callbackContext = callbackContext;

        if (action.equals(SCAN)) {
            return this.scan(callbackContext, data);
        } else if (action.equals(IS_WIFI_ENABLED)) {
            return this.isWifiEnabled(callbackContext);
        } else if (action.equals(SET_WIFI_ENABLED)) {
            return this.setWifiEnabled(callbackContext, data);
        } else if (!wifiManager.isWifiEnabled()) {
            callbackContext.error("Wifi is not enabled.");
            return false;
        } else if (action.equals(ADD_NETWORK)) {
            return this.addNetwork(callbackContext, data);
        } else if (action.equals(REMOVE_NETWORK)) {
            return this.removeNetwork(callbackContext, data);
        } else if (action.equals(CONNECT_NETWORK)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    connectNetwork(callbackContext, data);
                }
            });
            return true;
        } else if (action.equals(DISCONNECT_NETWORK)) {
            return this.disconnectNetwork(callbackContext, data);
        } else if (action.equals(LIST_NETWORKS)) {
            return this.listNetworks(callbackContext);
        } else if (action.equals(START_SCAN)) {
            startScanCallback = callbackContext;
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    startScan();
                }
            });
            return true;
        } else if (action.equals(GET_SCAN_RESULTS)) {
            scanWifiListCount = 0;
            return this.getScanResults(callbackContext, data);
        } else if (action.equals(DISCONNECT)) {
            return this.disconnect(callbackContext);
        } else if (action.equals(GET_CONNECTED_SSID)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    getConnectedSSID(callbackContext);
                }
            });
            return true;
        } else if (action.equals(GET_CONNECTED_SSID_WITHPERMISSION)) {
            this.getSSIDCallback = callbackContext;
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    getConnectedSSIDWithpermission();
                }
            });
            return true;
        } else if (action.equals(OPEN_LOCATION_SERVICE)) {
            this.openLocationServiceCallback = callbackContext;
            this.openLocationService();
            return true;
        } else if (action.equals(IS_5GHZ)) {
            cordova.getThreadPool().execute(new Runnable() {
                @Override
                public void run() {
                    is5GHz(callbackContext);
                }
            });
            return true;
        } else {
            callbackContext.error("Incorrect action parameter: " + action);
        }

        return false;
    }

    /**
     * Scans networks and sends the list back on the success callback
     *
     * @param callbackContext A Cordova callback context
     * @param data JSONArray with [0] == JSONObject
     * @return true
     */
    private boolean scan(final CallbackContext callbackContext, final JSONArray data) {
        if (!cordova.hasPermission(ACCESS_FINE_LOCATION)) {
            requestLocationPermission(SCAN_RESULTS_CODE);
            callbackContext.error("reject permission");
            return false;
        }

        Log.v(TAG, "Entering startScan");
        final ScanSyncContext syncContext = new ScanSyncContext();

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                Log.v(TAG, "Entering onReceive");

                synchronized (syncContext) {
                    if (syncContext.finished) {
                        Log.v(TAG, "In onReceive, already finished");
                        return;
                    }
                    syncContext.finished = true;
                    context.unregisterReceiver(this);
                }

                Log.v(TAG, "In onReceive, success");
                getScanResults(callbackContext, data);
            }
        };

        final Context context = cordova.getActivity().getApplicationContext();

        Log.v(TAG, "Submitting timeout to threadpool");

        cordova.getThreadPool().submit(new Runnable() {

            public void run() {

                Log.v(TAG, "Entering timeout");

                final int TEN_SECONDS = 10000;

                try {
                    Thread.sleep(TEN_SECONDS);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Received InterruptedException e, " + e);
                    // keep going into error
                }

                Log.v(TAG, "Thread sleep done");

                synchronized (syncContext) {
                    if (syncContext.finished) {
                        Log.v(TAG, "In timeout, already finished");
                        return;
                    }
                    syncContext.finished = true;
                    context.unregisterReceiver(receiver);
                }

                Log.v(TAG, "In timeout, error");
                callbackContext.error("TIMEOUT_WAITING_FOR_SCAN");
            }

        });

        Log.v(TAG, "Registering broadcastReceiver");
        context.registerReceiver(
            receiver,
            new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        );

        if (!wifiManager.startScan()) {
            Log.v(TAG, "Scan failed");
            callbackContext.error("SCAN_FAILED");
            return false;
        }

        Log.v(TAG, "Starting wifi scan");
        return true;
    }

    /**
     * Class to store finished boolean in
     */
    private class ScanSyncContext {
        public boolean finished = false;
    }

    /**
     * This methods adds a network to the list of available WiFi networks.
     * If the network already exists, then it updates it.
     *
     * @return true    if add successful, false if add fails
     * @params callbackContext     A Cordova callback context.
     * @params data                JSON Array with [0] == SSID, [1] == password
     */
    private boolean addNetwork(CallbackContext callbackContext, JSONArray data) {
        // Initialize the WifiConfiguration object
        WifiConfiguration wifi = new WifiConfiguration();

        Log.d(TAG, "WifiWizard: addNetwork entered.");

        try {
            // data's order for ANY object is 0: ssid, 1: authentication algorithm,
            // 2+: authentication information.
            String authType = data.getString(1);


            if (authType.equals("WPA")) {
                // WPA Data format:
                // 0: ssid
                // 1: auth
                // 2: password
                String newSSID = data.getString(0);
                wifi.SSID = newSSID;
                String newPass = data.getString(2);
                wifi.preSharedKey = newPass;

                wifi.status = WifiConfiguration.Status.ENABLED;
                wifi.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
                wifi.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
                wifi.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                wifi.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
                wifi.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
                wifi.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                wifi.allowedProtocols.set(WifiConfiguration.Protocol.WPA);

                wifi.networkId = ssidToNetworkId(newSSID);

                if (wifi.networkId == -1) {
                    wifiManager.addNetwork(wifi);
                    callbackContext.success(newSSID + " successfully added.");
                } else {
                    wifiManager.updateNetwork(wifi);
                    callbackContext.success(newSSID + " successfully updated.");
                }

                wifiManager.saveConfiguration();
                return true;
            } else if (authType.equals("WEP")) {
                // TODO: connect/configure for WEP
                Log.d(TAG, "WEP unsupported.");
                callbackContext.error("WEP unsupported");
                return false;
            } else if (authType.equals("NONE")) {
                String newSSID = data.getString(0);
                wifi.SSID = newSSID;
                wifi.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                wifi.networkId = ssidToNetworkId(newSSID);

                if (wifi.networkId == -1) {
                    wifiManager.addNetwork(wifi);
                    callbackContext.success(newSSID + " successfully added.");
                } else {
                    wifiManager.updateNetwork(wifi);
                    callbackContext.success(newSSID + " successfully updated.");
                }

                wifiManager.saveConfiguration();
                return true;
            }
            // TODO: Add more authentications as necessary
            else {
                Log.d(TAG, "Wifi Authentication Type Not Supported.");
                callbackContext.error("Wifi Authentication Type Not Supported: " + authType);
                return false;
            }
        } catch (Exception e) {
            callbackContext.error(e.getMessage());
            Log.d(TAG, e.getMessage());
            return false;
        }
    }

    /**
     * This method removes a network from the list of configured networks.
     *
     * @param callbackContext A Cordova callback context
     * @param data            JSON Array, with [0] being SSID to remove
     * @return true if network removed, false if failed
     */
    private boolean removeNetwork(CallbackContext callbackContext, JSONArray data) {
        Log.d(TAG, "WifiWizard: removeNetwork entered.");

        if (!validateData(data)) {
            callbackContext.error("WifiWizard: removeNetwork data invalid");
            Log.d(TAG, "WifiWizard: removeNetwork data invalid");
            return false;
        }

        // TODO: Verify the type of data!
        try {
            String ssidToDisconnect = data.getString(0);

            int networkIdToRemove = ssidToNetworkId(ssidToDisconnect);

            if (networkIdToRemove >= 0) {
                wifiManager.removeNetwork(networkIdToRemove);
                wifiManager.saveConfiguration();
                callbackContext.success("Network removed.");
                return true;
            } else {
                callbackContext.error("Network not found.");
                Log.d(TAG, "WifiWizard: Network not found, can't remove.");
                return false;
            }
        } catch (Exception e) {
            callbackContext.error(e.getMessage());
            Log.d(TAG, e.getMessage());
            return false;
        }
    }

    /**
     * This method connects a network.
     *
     * @param callbackContext A Cordova callback context
     * @param data            JSON Array, with [0] being SSID to connect
     * @return true if network connected, false if failed
     */
    private boolean connectNetwork(CallbackContext callbackContext, JSONArray data) {
        Log.d(TAG, "WifiWizard: connectNetwork entered.");
        if (!validateData(data)) {
            callbackContext.error("WifiWizard: connectNetwork invalid data");
            Log.d(TAG, "WifiWizard: connectNetwork invalid data.");
            return false;
        }
        String ssidToConnect = "";

        try {
            ssidToConnect = data.getString(0);
            Log.d(TAG, "ssidToConnect=" + ssidToConnect);
        } catch (Exception e) {
            callbackContext.error(e.getMessage());
            Log.d(TAG, e.getMessage());
            return false;
        }


        int networkIdToConnect = ssidToNetworkId(ssidToConnect);
        if (networkIdToConnect >= 0) {

            // We disable the network before connecting, because if this was the last connection before
            // a disconnect(), this will not reconnect.
            // wifiManager.disableNetwork(networkIdToConnect);
            wifiManager.enableNetwork(networkIdToConnect, true);

            SupplicantState supState;
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            supState = wifiInfo.getSupplicantState();
            callbackContext.success(supState.toString());
            return true;

        } else {
            callbackContext.error("WifiWizard: cannot connect to network");
            return false;
        }
//        return true;
    }

    /**
     * This method disconnects a network.
     *
     * @param callbackContext A Cordova callback context
     * @param data            JSON Array, with [0] being SSID to connect
     * @return true if network disconnected, false if failed
     */
    private boolean disconnectNetwork(CallbackContext callbackContext, JSONArray data) {
        Log.d(TAG, "WifiWizard: disconnectNetwork entered.");
        if (!validateData(data)) {
            callbackContext.error("WifiWizard: disconnectNetwork invalid data");
            Log.d(TAG, "WifiWizard: disconnectNetwork invalid data");
            return false;
        }
        String ssidToDisconnect = "";
        // TODO: Verify type of data here!
        try {
            ssidToDisconnect = data.getString(0);
        } catch (Exception e) {
            callbackContext.error(e.getMessage());
            Log.d(TAG, e.getMessage());
            return false;
        }

        int networkIdToDisconnect = ssidToNetworkId(ssidToDisconnect);

        if (networkIdToDisconnect > 0) {
            wifiManager.disableNetwork(networkIdToDisconnect);
            callbackContext.success("Network " + ssidToDisconnect + " disconnected!");
            return true;
        } else {
            callbackContext.error("Network " + ssidToDisconnect + " not found!");
            Log.d(TAG, "WifiWizard: Network not found to disconnect.");
            return false;
        }
    }

    /**
     * This method disconnects current network.
     *
     * @param callbackContext A Cordova callback context
     * @return true if network disconnected, false if failed
     */
    private boolean disconnect(CallbackContext callbackContext) {
        Log.d(TAG, "WifiWizard: disconnect entered.");
        if (wifiManager.disconnect()) {
            callbackContext.success("Disconnected from current network");
            return true;
        } else {
            callbackContext.error("Unable to disconnect from the current network");
            return false;
        }
    }

    /**
     * This method uses the callbackContext.success method to send a JSONArray
     * of the currently configured networks.
     *
     * @param callbackContext A Cordova callback context
     * @return true if network disconnected, false if failed
     */
    private boolean listNetworks(CallbackContext callbackContext) {
        Log.d(TAG, "WifiWizard: listNetworks entered.");
        List<WifiConfiguration> wifiList = wifiManager.getConfiguredNetworks();

        JSONArray returnList = new JSONArray();

        for (WifiConfiguration wifi : wifiList) {
            returnList.put(wifi.SSID);
        }

        callbackContext.success(returnList);

        return true;
    }

    /**
     * This method uses the callbackContext.success method to send a JSONArray
     * of the scanned networks.
     *
     * @param callbackContext A Cordova callback context
     * @param data            JSONArray with [0] == JSONObject
     * @return true
     */
    private boolean getScanResults(final CallbackContext callbackContext, final JSONArray data) {
        List<ScanResult> scanResults = wifiManager.getScanResults();

        JSONArray returnList = new JSONArray();

        Integer numLevels = null;

        if (!validateData(data)) {
            callbackContext.error("WifiWizard: disconnectNetwork invalid data");
            Log.d(TAG, "WifiWizard: disconnectNetwork invalid data");
            return false;
        } else if (!data.isNull(0)) {
            try {
                JSONObject options = data.getJSONObject(0);

                if (options.has("numLevels")) {
                    Integer levels = options.optInt("numLevels");

                    if (levels > 0) {
                        numLevels = levels;
                    } else if (options.optBoolean("numLevels", false)) {
                        // use previous default for {numLevels: true}
                        numLevels = 5;
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
                callbackContext.error(e.toString());
                return false;
            }
        }

        for (ScanResult scan : scanResults) {
            /*
             * @todo - breaking change, remove this notice when tidying new release and explain changes, e.g.:
             *   0.y.z includes a breaking change to WifiWizard.getScanResults().
             *   Earlier versions set scans' level attributes to a number derived from wifiManager.calculateSignalLevel.
             *   This update returns scans' raw RSSI value as the level, per Android spec / APIs.
             *   If your application depends on the previous behaviour, we have added an options object that will modify behaviour:
             *   - if `(n == true || n < 2)`, `*.getScanResults({numLevels: n})` will return data as before, split in 5 levels;
             *   - if `(n > 1)`, `*.getScanResults({numLevels: n})` will calculate the signal level, split in n levels;
             *   - if `(n == false)`, `*.getScanResults({numLevels: n})` will use the raw signal level;
             */

            int level;

            if (numLevels == null) {
                level = scan.level;
            } else {
                level = wifiManager.calculateSignalLevel(scan.level, numLevels);
            }

            JSONObject lvl = new JSONObject();
            try {
                lvl.put("level", level);
                lvl.put("SSID", scan.SSID);
                lvl.put("BSSID", scan.BSSID);
                lvl.put("frequency", scan.frequency);
                lvl.put("capabilities", scan.capabilities);
                // lvl.put("timestamp", scan.timestamp);
                returnList.put(lvl);
            } catch (JSONException e) {
                e.printStackTrace();
                callbackContext.error(e.toString());
                return false;
            }
        }
        Log.e("wifiList", returnList + "+++++++++");
        if (returnList.length() == 0) {
            if (scanWifiListCount < 3) {
                scanWifiListCount++;
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        getScanResults(callbackContext, data);
                    }
                }, 300);
            } else {
                callbackContext.error("reject permission");
            }
        } else {
            callbackContext.success(returnList);
        }
        return true;
    }

    /**
     * This method uses the callbackContext.success method. It starts a wifi scanning
     *
     * @return true if started was successful
     */
    private boolean startScan() {
        if (hasPermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !mLocationManager
                    .isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                startScanCallback.error("location service off");
                return false;
            }
            Log.e("location", "-->有权限");
        } else {
            cordova.requestPermissions(this, PERMISSION_REQUEST_CODE_GET_WIFI_LIST, perms);
//            ActivityCompat.requestPermissions(cordova.getActivity(), perms,
//                    PERMISSION_REQUEST_CODE);
            return false;
        }
        if (wifiManager.startScan()) {
            startScanCallback.success();
            return true;
        } else {
            startScanCallback.error("Scan failed");
            return false;
        }
    }

    /**
     * This method retrieves the SSID for the currently connected network
     *
     * @return true if SSID found, false if not.
     */
    private boolean getConnectedSSID(CallbackContext getSSIDCallback) {
        if (hasPermissions() || Build.VERSION.SDK_INT <= Build.VERSION_CODES.O) {
            Log.e("location", "-->有权限");
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O && !mLocationManager
                    .isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                getSSIDCallback.error("location service off");
                return false;
            }
        } else {
            getSSIDCallback.error("reject permission");
//            cordova.requestPermissions(this,PERMISSION_REQUEST_CODE,perms);
//            ActivityCompat.requestPermissions(cordova.getActivity(), perms,
//                    PERMISSION_REQUEST_CODE);
            return false;
        }
        if (!wifiManager.isWifiEnabled()) {
            getSSIDCallback.error("Wifi is disabled");
            return false;
        }

        WifiInfo info = wifiManager.getConnectionInfo();


        if (info == null) {
            getSSIDCallback.error("Unable to read wifi info");
            return false;
        }

        String ssid = info.getSSID();
        if (ssid == null || ssid.isEmpty()) {
            ssid = info.getBSSID();
        }
        if (ssid == null || ssid.isEmpty()) {
            getSSIDCallback.error("SSID is empty");
            return false;
        }

        if (Build.VERSION.SDK_INT >= 17) {
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
        }
        getSSIDCallback.success(ssid);
        return true;
    }

    private void openLocationService() {
        final Context context = cordova.getActivity();
        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            intent.setAction(Settings.ACTION_SETTINGS);
            try {
                context.startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * This method retrieves the SSID for the currently connected network
     *
     * @return true if SSID found, false if not.
     */
    private boolean getConnectedSSIDWithpermission() {

        if (hasPermissions() || Build.VERSION.SDK_INT <=Build.VERSION_CODES.O) {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O && !mLocationManager
                    .isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                getSSIDCallback.error("location service off");
                return false;
            }
        } else {
            cordova.requestPermissions(this, PERMISSION_REQUEST_CODE, perms);
            return false;
        }
        if (!wifiManager.isWifiEnabled()) {
            callbackSSID("Wifi is disabled");
            return false;
        }

        WifiInfo info = wifiManager.getConnectionInfo();


        if (info == null) {
            callbackSSID("Unable to read wifi info");
            return false;
        }

        String ssid = info.getSSID();
        if (ssid == null || ssid.isEmpty()) {
            ssid = info.getBSSID();
        }
        if (ssid == null || ssid.isEmpty()) {
            callbackSSID("SSID is empty");
            return false;
        }

        if (Build.VERSION.SDK_INT >= 17) {
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
        }
        getSSIDCallback.success(ssid);
        if(openLocationServiceCallback!=null)
        {
            openLocationServiceCallback.success(ssid);
        }
        return true;
    }

    public void callbackSSID(String call)
    {
        getSSIDCallback.error(call);
        if(openLocationServiceCallback!=null)
        {
            openLocationServiceCallback.error(call);
        }
    }


    /**
     * This method retrieves the current WiFi status
     *
     * @param callbackContext A Cordova callback context
     * @return true if WiFi is enabled, fail will be called if not.
     */
    private boolean isWifiEnabled(CallbackContext callbackContext) {
        boolean isEnabled = wifiManager.isWifiEnabled();
        callbackContext.success(isEnabled ? "1" : "0");
        return isEnabled;
    }

    /**
     * This method takes a given String, searches the current list of configured WiFi
     * networks, and returns the networkId for the network if the SSID matches. If not,
     * it returns -1.
     */
    private int ssidToNetworkId(String ssid) {
        List<WifiConfiguration> currentNetworks = wifiManager.getConfiguredNetworks();
        int networkId = -1;

        // For each network in the list, compare the SSID with the given one
        for (WifiConfiguration test : currentNetworks) {
            if (test.SSID.equals(ssid)) {
                networkId = test.networkId;
            }
        }

        return networkId;
    }

    /**
     * This method enables or disables the wifi
     */
    private boolean setWifiEnabled(CallbackContext callbackContext, JSONArray data) {
        if (!validateData(data)) {
            callbackContext.error("WifiWizard: disconnectNetwork invalid data");
            Log.d(TAG, "WifiWizard: disconnectNetwork invalid data");
            return false;
        }

        String status = "";

        try {
            status = data.getString(0);
        } catch (Exception e) {
            callbackContext.error(e.getMessage());
            Log.d(TAG, e.getMessage());
            return false;
        }

        if (wifiManager.setWifiEnabled(status.equals("true"))) {
            callbackContext.success();
            return true;
        } else {
            callbackContext.error("Cannot enable wifi");
            return false;
        }
    }

    private boolean validateData(JSONArray data) {
        try {
            if (data == null || data.get(0) == null) {
                callbackContext.error("Data is null.");
                return false;
            }
            return true;
        } catch (Exception e) {
            callbackContext.error(e.getMessage());
        }
        return false;
    }

    /**
     * 判断wifi是否为2.4G
     *
     * @param freq
     * @return
     */
    private static boolean is24GHz(int freq) {
        return freq > 2400 && freq < 2500;
    }

    /**
     * 判断wifi是否为5G
     *
     * @param freq
     * @return
     */
    private static boolean is5GHz(int freq) {
        return freq > 4900 && freq < 5900;
    }

    /**
     * 判断是否是5G
     */
    private boolean is5GHz(CallbackContext callbackContext) {
        if (!wifiManager.isWifiEnabled()) {
            callbackContext.error("Wifi is disabled");
            return false;
        }

        WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null) {
            callbackContext.error("Unable to read wifi info");
            return false;
        }
        String ssid = info.getSSID();
        if (ssid == null || ssid.isEmpty()) {
            ssid = info.getBSSID();
        }
        if (ssid == null || ssid.isEmpty()) {
            getSSIDCallback.error("SSID is empty");
            return false;
        }
        if (Build.VERSION.SDK_INT >= 21) {

            callbackContext.success(is5GHz(info.getFrequency()) ? 1 : 0);

        } else {
            if (Build.VERSION.SDK_INT >= 17) {
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
            }
            if (ssid != null && ssid.length() > 2) {
                List<ScanResult> scanResults = wifiManager.getScanResults();
                for (ScanResult scanResult : scanResults) {
                    if (scanResult.SSID.equals(ssid)) {
                        callbackContext.success(is5GHz(scanResult.frequency) ? 1 : 0);
                        break;
                    }
                }
            }
        }


        callbackContext.success(ssid);


        return false;
    }


    private boolean hasPermissions() {
        for (String perm : perms) {
            if (ContextCompat.checkSelfPermission(cordova.getActivity(), perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        doNext(requestCode, grantResults);
    }

    private void doNext(int requestCode, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE || requestCode == PERMISSION_REQUEST_CODE_GET_WIFI_LIST) {
            boolean isGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    isGranted = false;
                }
            }
            if (isGranted) { //允许
                Log.e("location", "允许了");
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    getConnectedSSIDWithpermission();
                } else {
                    startScan();
                }
            } else {
                Log.e("location", "不允许了");
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    getSSIDCallback.error("reject permission");
                } else {
                    startScanCallback.error("reject permission");
                }
            }
        }
    }

    
    /**
    * Request ACCESS_FINE_LOCATION Permission
    * @param requestCode
    */
    protected void requestLocationPermission(int requestCode) {
        cordova.requestPermission(this, requestCode, ACCESS_FINE_LOCATION);
    }


}
