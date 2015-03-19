package org.aquto.cordova.vpn;

import android.annotation.TargetApi;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
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
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        mContext.registerReceiver(this, filter);
    }

    public void unregister() {
        mContext.unregisterReceiver(this);
    }

    @SuppressWarnings("deprecation")
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private boolean isAirplaneModeOn() {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1)
            return Settings.System.getInt(mContext.getContentResolver(), Settings.System.AIRPLANE_MODE_ON, 0) != 0;
        else
            return Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    public boolean connectionValid() {
        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        if(info == null)
            return false;
        return (allowWiFi || !disallowedNetworkTypes.contains(info.getType())) && !isAirplaneModeOn();
    }

    private void stopVpn() {
        Log.d(TAG, "Transitioned to a disallowed network type so stopping the vpn connection.");
        Intent intent = new Intent(mContext, CharonVpnService.class);
        mContext.startService(intent);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean noConnectivity = intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
        boolean isFailover = intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_FAILOVER, false);
        if(noConnectivity || isFailover || !connectionValid())
            stopVpn();
    }
}