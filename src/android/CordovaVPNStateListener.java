package org.aquto.cordova.vpn;

import org.apache.cordova.*;
import org.strongswan.android.logic.VpnStateService;

public class CordovaVPNStateListener implements VpnStateService.VpnStateListener {
    private CallbackContext callbackContext;
    private VpnStateService mService;

    private static final String DISCONNECTED = "DISCONNECTED";

    public CordovaVPNStateListener(CallbackContext _callbackContext, VpnStateService _mService) {
        callbackContext = _callbackContext;
        mService = _mService;
    }

    @Override
    public void stateChanged() {
        VpnStateService.ErrorState eState = mService.getErrorState();
        VpnStateService.State newState = mService.getState();
        PluginResult pr;
        if(eState != VpnStateService.ErrorState.NO_ERROR) {
            pr = new PluginResult(PluginResult.Status.ERROR, errorToString(eState));
        } else {
            pr = new PluginResult(PluginResult.Status.OK, stateToString(newState));
            pr.setKeepCallback(true);
        }
        callbackContext.sendPluginResult(pr);
        if(eState != VpnStateService.ErrorState.NO_ERROR || newState == VpnStateService.State.DISABLED)
            mService.unregisterListener(this);
    }

    private String errorToString(VpnStateService.ErrorState state) {
        if(state == VpnStateService.ErrorState.PEER_AUTH_FAILED)
            return VpnStateService.ErrorState.AUTH_FAILED.toString();
        else if(state == VpnStateService.ErrorState.GENERIC_ERROR)
            return VPNManager.ErrorCode.UNKNOWN_ERROR.toString();
        else if(state == VpnStateService.ErrorState.LOOKUP_FAILED)
            return VpnStateService.ErrorState.UNREACHABLE.toString();
        else
            return state.toString();
    }

    private String stateToString(VpnStateService.State state) {
        if(state == VpnStateService.State.DISABLED)
            return DISCONNECTED;
        else
            return state.toString();
    }
}