package org.aquto.cordova.vpn;

import android.content.*;
import android.content.pm.PackageManager;
import android.app.Service;
import android.net.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.util.List;
import java.security.KeyStore;
import java.security.cert.*;
import org.apache.cordova.*;
import org.strongswan.android.logic.*;
import org.strongswan.android.data.*;
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
        public static final String NEEDS_PROFILE = "needsProfile";
        public static final String STATUS = "status";
        public static final String IS_VPN_CAPABLE = "isVpnCapable";
        public static final String ENABLE = "enable";
        public static final String DISABLE = "disable";
        public static final String REGISTER_CALLBACK = "registerCallback";
        public static final String UNREGISTER_CALLBACK = "unregisterCallback";
    }

    private final class JSONParameters {
        public static final String VPN_HOST = "vpnHost";
        public static final String VPN_USERNAME = "vpnUsername";
        public static final String VPN_PASSWORD = "vpnPassword";
        public static final String UP = "up";
        public static final String USER_CERTIFICATE = "userCertificate";
        public static final String USER_CERTIFICATE_PASSWORD = "userCertificatePassword";
        public static final String CA_CERTIFICATE = "caCertificate";
    }

    private static final String TAG = VPNManager.class.getSimpleName();
    private static final int RESULT_OK = -1;
    private static final int PREPARE_VPN_SERVICE = 0;

    private ConnectionValidityChecker validityChecker;
    private CallbackContext callbackContext;
    private VpnProfile vpnInfo;
    private VpnStateService mService;
    private CordovaVPNStateListener stateListener;
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
        if(mService != null)
            cordova.getActivity().unbindService(mServiceConnection);
        validityChecker.unregister();
    }

    private PluginResult error(ErrorCode error) {
        return new PluginResult(PluginResult.Status.ERROR, error.toString());
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
                return new PluginResult(PluginResult.Status.OK, VpnStateService.State.CONNECTING.toString());
            } catch(ActivityNotFoundException ex) {
                /* it seems some devices, even though they come with Android 4,
                * don't have the VPN components built into the system image.
                * com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog
                * will not be found then */
                return error(ErrorCode.NOT_SUPPORTED);
            }
        } else {
            /* user already granted permission to use VpnService */
            enableConnection(profile);
            return new PluginResult(PluginResult.Status.OK, VpnStateService.State.CONNECTING.toString());
        }
    }

    private void enableConnection(VpnProfile profile) {
        Intent cintent = new Intent(cordova.getActivity(), CharonVpnService.class);
        cintent.putExtra(CharonVpnService.PROFILE_BUNDLE_KEY, vpnInfo);
        cordova.getActivity().startService(cintent);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        switch(requestCode) {
            case PREPARE_VPN_SERVICE:
                if(resultCode == RESULT_OK)
                    enableConnection(vpnInfo);
                else
                    callbackContext.sendPluginResult(error(ErrorCode.PERMISSION_NOT_GRANTED));
                break;
            default:
                super.onActivityResult(requestCode, resultCode, intent);
        }
    }

    private VpnProfile toVpnProfile(JSONObject provisioningJson) throws Exception {
        String gateway, username, password, userCertPassword, b64UserCert, b64CaCert;
        gateway = provisioningJson.getString(JSONParameters.VPN_HOST);
        username = provisioningJson.getString(JSONParameters.VPN_USERNAME);
        password = provisioningJson.getString(JSONParameters.VPN_PASSWORD);
        b64UserCert = provisioningJson.getString(JSONParameters.USER_CERTIFICATE);
        userCertPassword = provisioningJson.getString(JSONParameters.USER_CERTIFICATE_PASSWORD);
        b64CaCert = provisioningJson.getString(JSONParameters.CA_CERTIFICATE);
        if(gateway == null || username == null || password == null || b64UserCert == null || userCertPassword == null || b64CaCert == null)
            return null;

        // Prepare the VPN profile object
        VpnProfile vpnInfo = new VpnProfile();
        vpnInfo.setGateway(gateway);
        vpnInfo.setUsername(username);
        vpnInfo.setPassword(password);
        vpnInfo.setVpnType(VpnType.IKEV2_CERT_EAP);
        vpnInfo.setUserCertificateAlias(username + "@" + gateway);
        vpnInfo.setUserCertificatePassword(userCertPassword);

        // Import the user certificate
        UserCredentialManager.getInstance().storeCredentials(b64UserCert.getBytes(), userCertPassword.toCharArray());

        // Decode the CA certificate from base64 to an X509Certificate
        byte[] decoded = android.util.Base64.decode(b64CaCert.getBytes(), 0);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        InputStream in = new ByteArrayInputStream(decoded);
        X509Certificate certificate = (X509Certificate)factory.generateCertificate(in);

        // And then import it into the Strongswan LocalCertificateStore
        KeyStore store = KeyStore.getInstance("LocalCertificateStore");
        store.load(null, null);
        store.setCertificateEntry(null, certificate);
        TrustedCertificateManager.getInstance().reset();

        return vpnInfo;
    }

    private PluginResult handleNeedsProfileAction(CallbackContext callbackContext) {
        return new PluginResult(PluginResult.Status.OK, false);
    }

    private PluginResult handleStatusAction() {
        if(mService != null)
            return new PluginResult(PluginResult.Status.OK, StateConversion.stateToString(mService.getState()));
        else
            return new PluginResult(PluginResult.Status.OK, StateConversion.stateToString(VpnStateService.State.DISABLED));
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
                return prepareVpnService(profile, callbackContext);
            } else
                return error(ErrorCode.DISALLOWED_NETWORK_TYPE);
        } catch(JSONException je) {
            return error(ErrorCode.MISSING_FIELDS);
        } catch(Exception e) {
            Log.e(TAG, "Unknown error enabling VPN", e);
            return error(ErrorCode.UNKNOWN_ERROR);
        }
    }

    private PluginResult handleDisableAction() {
        // tear down the active VPN connection
        if(mService != null)
            mService.disconnect();
        return new PluginResult(PluginResult.Status.OK, true);
    }

    private PluginResult handleRegisterCallbackAction(CallbackContext callbackContext) {
        if(stateListener != null)
            mService.unregisterListener(stateListener);
        stateListener = new CordovaVPNStateListener(callbackContext, mService);
        mService.registerListener(stateListener);
        PluginResult res = new PluginResult(PluginResult.Status.OK, true);
        res.setKeepCallback(true);
        return res;
    }

    private PluginResult handleUnregisterCallbackAction(CallbackContext callbackContext) {
        if(stateListener != null) {
            mService.unregisterListener(stateListener);
            stateListener = null;
        }
        return new PluginResult(PluginResult.Status.OK, true);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        if(action.equals(PluginActions.NEEDS_PROFILE))
            callbackContext.sendPluginResult(handleNeedsProfileAction(callbackContext));
        else if(action.equals(PluginActions.STATUS))
            callbackContext.sendPluginResult(handleStatusAction());
        else if(action.equals(PluginActions.IS_VPN_CAPABLE))
            callbackContext.sendPluginResult(handleIsVpnCapableAction());
        else if(action.equals(PluginActions.ENABLE))
            callbackContext.sendPluginResult(handleEnableAction(args, callbackContext));
        else if(action.equals(PluginActions.DISABLE))
            callbackContext.sendPluginResult(handleDisableAction());
        else if(action.equals(PluginActions.REGISTER_CALLBACK))
            callbackContext.sendPluginResult(handleRegisterCallbackAction(callbackContext));
        else if(action.equals(PluginActions.UNREGISTER_CALLBACK))
            callbackContext.sendPluginResult(handleUnregisterCallbackAction(callbackContext));
        else
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION, ""));
        return true;
    }
}
