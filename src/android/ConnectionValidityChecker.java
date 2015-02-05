package org.aquto.cordova.vpn;

import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.Bundle;
import android.util.Log;
import java.util.*;
import org.strongswan.android.logic.CharonVpnService;

public class ConnectionValidityChecker extends BroadcastReceiver {

    private static final String TAG = ConnectionValidityChecker.class.getSimpleName();

    /**
     * terminate the VPN connection if the active connection changes to one of the below types.
     */
    public static final HashSet<Integer> disallowedNetworkTypes = new HashSet<Integer>(Arrays.asList(new Integer[] {
                            ConnectivityManager.TYPE_ETHERNET,
                            ConnectivityManager.TYPE_WIFI,
                            ConnectivityManager.TYPE_WIMAX
                    }));

    private boolean allowWiFi;
    private final static String ALLOWWIFI_KEY = "allowWiFi";
    private final Context mContext;

    public ConnectionValidityChecker(Context context) {
        mContext = context;
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(mContext.getPackageName(), PackageManager.GET_META_DATA);
            Bundle bundle = ai.metaData;
            allowWiFi = bundle.getBoolean(ALLOWWIFI_KEY, false);
        } catch(PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to load meta-data, NameNotFound: " + e.getMessage());
            allowWiFi = false;
        }
    }

    public void register() {
        if(!allowWiFi)
            mContext.registerReceiver(this, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    public void unregister() {
        if(!allowWiFi)
            mContext.unregisterReceiver(this);
    }

    public boolean connectionValid() {
        if(allowWiFi)
            return true;
        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        //assume null connection info to be valid
        return info == null || !disallowedNetworkTypes.contains(info.getType());
    }

    private void stopVpn() {
        Log.d(TAG, "Transitioned to a disallowed network type so stopping the vpn connection.");
        Intent intent = new Intent(mContext, CharonVpnService.class);
        mContext.startService(intent);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if(!connectionValid()) {
            stopVpn();
        }
    }
}