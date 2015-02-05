/*
 * Copyright (C) 2014-2015 Paul Kinsky
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.  See <http://www.fsf.org/copyleft/gpl.txt>.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 */

package org.aquto.cordova.vpn;

import android.content.*;
import android.content.pm.PackageManager;
import android.app.Service;
import android.net.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.security.KeyStore;
import java.util.List;
import org.apache.cordova.*;
import org.strongswan.android.logic.CharonVpnService;
import org.strongswan.android.logic.VpnStateService;
import org.strongswan.android.data.VpnProfile;
import org.strongswan.android.data.VpnType;
import org.json.*;

public class VPNManager extends CordovaPlugin {

    public enum ErrorCode {
        NOT_SUPPORTED,
        MISSING_FIELDS,
        UNKNOWN_ERROR,
        PERMISSION_NOT_GRANTED,
        DISALLOWED_NETWORK_TYPE
    }

    private final class PluginActions {
        public static final String IS_UP = "isUp";
        public static final String STATUS = "status";
        public static final String IS_VPN_CAPABLE = "isVpnCapable";
        public static final String ENABLE = "enable";
        public static final String DISABLE = "disable";
    }

    private static final String TAG = VPNManager.class.getSimpleName();
    private static final int RESULT_OK = -1;
    private static final int PREPARE_VPN_SERVICE = 0;

    private ConnectionValidityChecker validityChecker;
    private VpnProfile vpnInfo = null;
    private CallbackContext callbackContext;
    private VpnStateService mService;
    private final Object mServiceLock = new Object();
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized(mServiceLock) {
                mService = null;
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized(mServiceLock) {
                mService = ((VpnStateService.LocalBinder)service).getService();
            }
        }
    };

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        validityChecker = new ConnectionValidityChecker(cordova.getActivity());
        validityChecker.register();
        Intent stateIntent = new Intent(cordova.getActivity(), VpnStateService.class);
        cordova.getActivity().startService(stateIntent);
        cordova.getActivity().bindService(stateIntent, mServiceConnection, Service.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy() {
        if(mService != null) {
            cordova.getActivity().unbindService(mServiceConnection);
        }
        validityChecker.unregister();
    }

    private PluginResult error(ErrorCode error) {
        PluginResult.Status status = PluginResult.Status.ERROR;
        return new PluginResult(status, error.toString());
    }

    /**
    * Prepare the VpnService. If this succeeds the current VPN profile is
    * started.
    * @param profileInfo a bundle containing the information about the profile to be started
    */
    protected PluginResult prepareVpnService(VpnProfile profile, CallbackContext callbackContext) {
        this.callbackContext = callbackContext;
        this.vpnInfo = profile;
        Intent intent;
        try {
            intent = VpnService.prepare(cordova.getActivity());
        } catch(IllegalStateException ex) {
            /* this happens if the always-on VPN feature (Android 4.2+) is activated */
            return error(ErrorCode.NOT_SUPPORTED);
        }
        if(intent != null) {
            try {
                cordova.startActivityForResult((CordovaPlugin)this, intent, PREPARE_VPN_SERVICE);
                PluginResult result = new PluginResult(PluginResult.Status.OK, VpnStateService.State.CONNECTING.toString());
                result.setKeepCallback(true);
                return result;
            } catch(ActivityNotFoundException ex) {
                /* it seems some devices, even though they come with Android 4,
                * don't have the VPN components built into the system image.
                * com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog
                * will not be found then */
                return error(ErrorCode.NOT_SUPPORTED);
            }
        } else {
            /* user already granted permission to use VpnService */
            enableConnection(profile, callbackContext);
            PluginResult result = new PluginResult(PluginResult.Status.OK, VpnStateService.State.CONNECTING.toString());
            result.setKeepCallback(true);
            return result;
        }
    }

    private void enableConnection(VpnProfile profile, CallbackContext callbackContext) {
        mService.registerListener(new CordovaVPNStateListener(callbackContext, mService));
        Intent cintent = new Intent(cordova.getActivity(), CharonVpnService.class);
        cintent.putExtra("profile", vpnInfo);
        cordova.getActivity().startService(cintent);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        switch(requestCode) {
            case PREPARE_VPN_SERVICE:
                if(resultCode == RESULT_OK) {
                    enableConnection(vpnInfo, callbackContext);
                } else
                    callbackContext.sendPluginResult(error(ErrorCode.PERMISSION_NOT_GRANTED));
                break;
            default:
                super.onActivityResult(requestCode, resultCode, intent);
        }
    }

    private VpnProfile toVpnProfile(JSONObject provisioningJson) throws JSONException {
        String gateway, username, password;
        gateway = provisioningJson.getString("vpnHost");
        username = provisioningJson.getString("vpnUsername");
        password = provisioningJson.getString("vpnPassword");
        if(gateway == null || username == null || password == null)
            return null;
        VpnProfile vpnInfo = new VpnProfile();
        vpnInfo.setGateway(gateway);
        vpnInfo.setUsername(username);
        vpnInfo.setPassword(password);
        vpnInfo.setVpnType(VpnType.IKEV2_CERT_EAP);
        vpnInfo.setUserCertificateAlias(username + "@" + gateway);
        return vpnInfo;
    }

    private void createKeystore(JSONObject provisioningJson) throws Exception {
        String b64Cert, certPassword;
        b64Cert = provisioningJson.getString("certificate");
        certPassword = provisioningJson.getString("certificatePassword");

        KeyStore keystore = KeyStore.getInstance("PKCS12");
        byte[] cert = android.util.Base64.decode(b64Cert, 0);

        InputStream is = new java.io.ByteArrayInputStream(cert);
        keystore.load(is, certPassword.toCharArray());
        is.close();

        FileOutputStream fos = cordova.getActivity().openFileOutput(CharonVpnService.keystoreFile, Context.MODE_PRIVATE);
        keystore.store(fos, CharonVpnService.keystorePass.toCharArray());
        fos.close();
    }

    private PluginResult handleIsUpAction() {
        //this file will exist iff there is an active VPN connection
        File vpn = new File("/sys/class/net/tun0");
        return new PluginResult(PluginResult.Status.OK, vpn.exists());
    }

    private PluginResult handleStatusAction() {
        //this file will exist iff there is an active VPN connection
        File vpn = new File("/sys/class/net/tun0");
        JSONObject statusObj = new JSONObject();
        try {
            statusObj.put("up", vpn.exists());
            return new PluginResult(PluginResult.Status.OK, statusObj);
        } catch(JSONException je) {
            return error(ErrorCode.UNKNOWN_ERROR);
        } catch(Exception e) {
            return error(ErrorCode.UNKNOWN_ERROR);
        }
    }

    private PluginResult handleIsVpnCapableAction() {
        boolean result;
        try {
            final Intent intent = VpnService.prepare(cordova.getActivity());
            final PackageManager packageManager = cordova.getActivity().getPackageManager();
            if(intent != null) {
                List resolveInfo = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
                result = (resolveInfo.size() > 0);
            } else {
                /* user already granted permission to use VpnService */
                result = true;
            }
        } catch(IllegalStateException ex) {
            /* this happens if the always-on VPN feature (Android 4.2+) is activated */
            result = false;
        }
        return new PluginResult(PluginResult.Status.OK, result);
    }

    private PluginResult handleEnableAction(JSONArray args, CallbackContext callbackContext) {
        try {
            if(validityChecker.connectionValid()) {
                JSONObject provisioningJson = args.getJSONObject(0);
                VpnProfile profile = toVpnProfile(provisioningJson);
                if(profile == null)
                    return error(ErrorCode.MISSING_FIELDS);
                createKeystore(provisioningJson);
                return prepareVpnService(profile, callbackContext);
            } else
                return error(ErrorCode.DISALLOWED_NETWORK_TYPE);
        } catch(JSONException je) {
            return error(ErrorCode.MISSING_FIELDS);
        } catch(Exception e) {
            Log.e(TAG, "error enabling VPN", e);
            return error(ErrorCode.UNKNOWN_ERROR);
        }
    }

    private PluginResult handleDisableAction() {
        // tear down the active VPN connection
        Intent intent = new Intent(cordova.getActivity(), CharonVpnService.class);
        cordova.getActivity().startService(intent);
        return new PluginResult(PluginResult.Status.OK, true);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        if(action.equals(PluginActions.IS_UP))
            callbackContext.sendPluginResult(handleIsUpAction());
        else if(action.equals(PluginActions.STATUS))
            callbackContext.sendPluginResult(handleStatusAction());
        else if(action.equals(PluginActions.IS_VPN_CAPABLE))
            callbackContext.sendPluginResult(handleIsVpnCapableAction());
        else if(action.equals(PluginActions.ENABLE))
            callbackContext.sendPluginResult(handleEnableAction(args, callbackContext));
        else if(action.equals(PluginActions.DISABLE))
            callbackContext.sendPluginResult(handleDisableAction());
        else
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION, ""));
        return true;
    }
}
