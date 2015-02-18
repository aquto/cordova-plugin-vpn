package org.aquto.cordova.vpn;

import org.apache.cordova.*;
import org.strongswan.android.logic.VpnStateService;

public class CordovaVPNStateListener implements VpnStateService.VpnStateListener {
    private CallbackContext callbackContext;
    private VpnStateService mService;

    public CordovaVPNStateListener(CallbackContext _callbackContext, VpnStateService _mService) {
        callbackContext = _callbackContext;
        mService = _mService;
    }

    @Override
    public void stateChanged() {
        VpnStateService.ErrorState eState = mService.getErrorState();
        VpnStateService.State newState = mService.getState();
        PluginResult pr;
        if(eState != VpnStateService.ErrorState.NO_ERROR)
            pr = new PluginResult(PluginResult.Status.ERROR, StateConversion.errorToString(eState));
        else
            pr = new PluginResult(PluginResult.Status.OK, StateConversion.stateToString(newState));
        pr.setKeepCallback(true);
        callbackContext.sendPluginResult(pr);
    }
}