/*
 * Copyright (C) 2012-2013 Tobias Brunner
 * Hochschule fuer Technik Rapperswil
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

package org.strongswan.android.logic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
import java.util.*;

public class NetworkManager extends BroadcastReceiver
{
	private static final String TAG = NetworkManager.class.getSimpleName();
    
        /**
         * terminate the VPN connection if the active connection changes to one of the below types.
         */
        public static final HashSet<Integer> disallowedNetworkTypes = new HashSet<Integer>(Arrays.asList(new Integer[] {
                    ConnectivityManager.TYPE_ETHERNET,
                    ConnectivityManager.TYPE_WIFI,
                    ConnectivityManager.TYPE_WIMAX
                }));

        private final boolean mobileOnly;

	private final Context mContext;
	private boolean mRegistered;

	public NetworkManager(Context context)
	{
		mContext = context;
                int mobileOnlyId = context.getResources().getIdentifier("mobile_only", "bool", context.getPackageName());
                mobileOnly = context.getResources().getBoolean(mobileOnlyId);       
	}

	public void Register()
	{
	    mContext.registerReceiver(this, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
	}

	public void Unregister()
	{
	    mContext.unregisterReceiver(this);
	}

        //assume null connection info to be valid
        public static boolean connectionValid(NetworkInfo info){
           return info == null || !disallowedNetworkTypes.contains(info.getType());
        }

        private void stopVpn(){
            Log.d(TAG, "moved to disallowed network type, spiking vpn connection");
            Intent intent = new Intent(mContext, CharonVpnService.class);
            intent.putExtra(CharonVpnService.STOP_REASON, CharonVpnService.DISALLOWED_NETWORK_STOP_REASON);
            mContext.startService(intent);
        }

	@Override
	public void onReceive(Context context, Intent intent)
	{
		ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo info = cm.getActiveNetworkInfo();

                if (mobileOnly && !connectionValid(info)){
                    stopVpn();
                }
                
		networkChanged(info == null || !info.isConnected());
	}

	/**
	 * Notify the native parts about a network change
	 *
	 * @param disconnected true if no connection is available at the moment
	 */
	public native void networkChanged(boolean disconnected);
}
