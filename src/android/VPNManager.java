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

import org.json.JSONArray;

import android.os.Bundle;
import android.util.Log;
import android.os.Environment;
import org.json.JSONObject;
import android.content.ActivityNotFoundException;
import android.net.VpnService;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import java.io.*;
import android.content.Intent;
import java.security.KeyStore;
import android.net.ConnectivityManager;

import android.content.Context;
import org.strongswan.android.logic.CharonVpnService;
import org.strongswan.android.logic.NetworkManager;
import android.net.NetworkInfo;
import org.json.JSONException;
import android.content.pm.PackageManager;
import java.util.List;

public class VPNManager extends CordovaPlugin {

    public enum ErrorCode {
       NOT_SUPPORTED,
       MISSING_FIELDS,
       UNKNOWN_ERROR,
       PERMISSION_NOT_GRANTED,
       DISALLOWED_NETWORK_TYPE
    }

    private static final String TAG = "VPNManager";
    private static final int REQUEST_CODE_RESOLVE_ERR = 9000;
    private static final int RESULT_OK = -1;

    private static final int PREPARE_VPN_SERVICE = 0;

    private VpnProfile vpnInfo = null;

    private CallbackContext callbackContext;

    private boolean isDeviceVpnCapable()
    {
      try{
        final Intent intent = VpnService.prepare(cordova.getActivity());
        final PackageManager packageManager = cordova.getActivity().getPackageManager();
        if (intent != null){
          List resolveInfo = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
          return resolveInfo.size() > 0;
        } else { /* user already granted permission to use VpnService */
          return true;
        }
      }
      catch (IllegalStateException ex)
      { /* this happens if the always-on VPN feature (Android 4.2+) is activated */
        return false;
      }
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
    protected void prepareVpnService(CallbackContext callbackContext)
    {
      this.callbackContext = callbackContext;
      Intent intent;
      try
      {
        intent = VpnService.prepare(cordova.getActivity());
      }
      catch (IllegalStateException ex)
      {
        /* this happens if the always-on VPN feature (Android 4.2+) is activated */
        callbackContext.sendPluginResult(error(ErrorCode.NOT_SUPPORTED));
        return;
      }
      if (intent != null)
      {
        try
        {
          cordova.startActivityForResult((CordovaPlugin) this, intent, PREPARE_VPN_SERVICE);
          return;
        }
        catch (ActivityNotFoundException ex)
        {
          /* it seems some devices, even though they come with Android 4,
          * don't have the VPN components built into the system image.
          * com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog
          * will not be found then */
          callbackContext.sendPluginResult(error(ErrorCode.NOT_SUPPORTED));
          return;
        }
      }
      else
      {  /* user already granted permission to use VpnService */
        onActivityResult(PREPARE_VPN_SERVICE, RESULT_OK, null);
        return;
      }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    switch (requestCode)
    {
      case PREPARE_VPN_SERVICE:
        if (resultCode == RESULT_OK)
        {
          CharonVpnService.registerCallback(callbackContext);

          Intent cintent = new Intent(cordova.getActivity(), CharonVpnService.class);
          cintent.putExtras(vpnInfo.toBundle());
          cordova.getActivity().startService(cintent);
        }else{
          callbackContext.sendPluginResult(error(ErrorCode.PERMISSION_NOT_GRANTED));
        }
        break;
      default:
        super.onActivityResult(requestCode, resultCode, intent);
    }
    }

    private boolean connectionValid(){
      ConnectivityManager cm = (ConnectivityManager) cordova.getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
      NetworkInfo info = cm.getActiveNetworkInfo();
      return NetworkManager.connectionValid(info);
    }

    private void enableConnection(JSONObject provisioningJson, CallbackContext context) throws Exception {

      String rawCert                    = null;
      String vpnHost                    = null;
      String vpnPassword                = null;
      String certificatePassword        = null;
      String vpnUsername                = null;
      //get default value from config
      int vpnConnectionTimeoutMillisId  = cordova.getActivity().getResources().getIdentifier("vpn_default_timeout", "integer", cordova.getActivity().getPackageName());
      int vpnConnectionTimeoutMillis   = cordova.getActivity().getResources().getInteger(vpnConnectionTimeoutMillisId);

      Log.d(TAG, "loaded vpn default timeout: " + vpnConnectionTimeoutMillis);

      try{
        rawCert = provisioningJson.getString("certificate");
        vpnHost = provisioningJson.getString("vpnHost");
        vpnPassword = provisioningJson.getString("vpnPassword");
        certificatePassword = provisioningJson.getString("certificatePassword");
        vpnUsername = provisioningJson.getString("vpnUsername");
        vpnConnectionTimeoutMillis = provisioningJson.getInt("vpnConnectionTimeoutMillis");
      } catch (JSONException j){}

      if (rawCert != null && vpnHost != null && vpnPassword != null && certificatePassword != null && vpnUsername != null){

        int vpnNameId = cordova.getActivity().getResources().getIdentifier("vpn_name", "string", cordova.getActivity().getPackageName());
        String vpnName = cordova.getActivity().getResources().getString(vpnNameId);
        vpnInfo = new VpnProfile(vpnName, vpnHost, vpnUsername, vpnPassword, vpnConnectionTimeoutMillis);

        KeyStore keystore = KeyStore.getInstance("PKCS12");
        byte[] cert = android.util.Base64.decode(rawCert, 0);

        InputStream is = new java.io.ByteArrayInputStream(cert);
        keystore.load(is, certificatePassword.toCharArray());
        is.close();

        FileOutputStream fos = cordova.getActivity().openFileOutput(CharonVpnService.keystoreFile, Context.MODE_PRIVATE);
        keystore.store(fos, CharonVpnService.keystorePass.toCharArray());
        fos.close();

        prepareVpnService(context);
      } else {
        context.sendPluginResult(error(ErrorCode.MISSING_FIELDS));
      }
    }


    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
      if (action.equals("isUp")) {
        //this file will exist iff there is an active VPN connection
        File vpn = new File("/sys/class/net/tun0");
        PluginResult.Status status = PluginResult.Status.OK;
        callbackContext.sendPluginResult(new PluginResult(status, vpn.exists()));
      }
      else if (action.equals("status")) {
        //this file will exist iff there is an active VPN connection
        File vpn = new File("/sys/class/net/tun0");
        PluginResult.Status status = PluginResult.Status.OK;

        JSONObject statusObj = new JSONObject();
        try{
          statusObj.put("up", vpn.exists());
          callbackContext.sendPluginResult(new PluginResult(status, statusObj));
        } catch (JSONException je){
          callbackContext.sendPluginResult(error(ErrorCode.UNKNOWN_ERROR));
        } catch (Exception e){
          callbackContext.sendPluginResult(error(ErrorCode.UNKNOWN_ERROR));
        }

      }

      // check if this device is capable of connecting to a VPN
      else if (action.equals("isVpnCapable")) {
        PluginResult.Status status = PluginResult.Status.OK;
        callbackContext.sendPluginResult(new PluginResult(status, isDeviceVpnCapable()));
      }
      // attempt to enable the VPN
      else if (action.equals("enable")) {
        try{
          int mobileOnlyId = cordova.getActivity().getResources().getIdentifier("mobile_only", "bool", cordova.getActivity().getPackageName());
          boolean mobileOnly = cordova.getActivity().getResources().getBoolean(mobileOnlyId);
          if (!mobileOnly || connectionValid()){
            JSONObject provisioningJson = args.getJSONObject(0);
            enableConnection(provisioningJson, callbackContext);
          } else {
            callbackContext.sendPluginResult(error(ErrorCode.DISALLOWED_NETWORK_TYPE));
          }
        } catch (JSONException je){
          callbackContext.sendPluginResult(error(ErrorCode.MISSING_FIELDS));
        } catch (Exception e){
          Log.e(TAG, "error enabling VPN", e);
          callbackContext.sendPluginResult(error(ErrorCode.UNKNOWN_ERROR));
        }
      }
      // tear down the active VPN connection
      else if (action.equals("disable")) {
        Intent intent = new Intent(cordova.getActivity(), CharonVpnService.class);
        intent.putExtra(CharonVpnService.STOP_REASON, "manual");
        cordova.getActivity().startService(intent);
        PluginResult.Status status = PluginResult.Status.OK;
        callbackContext.sendPluginResult(new PluginResult(status, true));
      }
      else {
        PluginResult.Status status = PluginResult.Status.OK;
        String result = "";
        status = PluginResult.Status.INVALID_ACTION;
        callbackContext.sendPluginResult(new PluginResult(status, result));
      }
      return true;
    }


}
