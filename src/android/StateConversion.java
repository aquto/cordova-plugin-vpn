package org.aquto.cordova.vpn;

import org.strongswan.android.logic.VpnStateService;

public class StateConversion {

    private static final String DISCONNECTED = "DISCONNECTED";

    public static String errorToString(VpnStateService.ErrorState state) {
        if(state == VpnStateService.ErrorState.PEER_AUTH_FAILED)
            return VpnStateService.ErrorState.AUTH_FAILED.toString();
        else if(state == VpnStateService.ErrorState.GENERIC_ERROR)
            return VPNManager.ErrorCode.UNKNOWN_ERROR.toString();
        else if(state == VpnStateService.ErrorState.LOOKUP_FAILED)
            return VpnStateService.ErrorState.UNREACHABLE.toString();
        else
            return state.toString();
    }

    public static String stateToString(VpnStateService.State state) {
        if(state == VpnStateService.State.DISABLED)
            return DISCONNECTED;
        else
            return state.toString();
    }
}