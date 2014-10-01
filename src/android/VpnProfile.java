/*
 * Copyright (C) 2012 Tobias Brunner
 * Copyright (C) 2012 Giuliano Grassi
 * Copyright (C) 2012 Ralf Sager
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

package org.aquto.cordova.vpn;

import android.os.Bundle;

public class VpnProfile implements Cloneable
{
	public final String name, gateway, username, password, alias;
	public final VpnType vpnType = VpnType.IKEV2_CERT_EAP; //hardcoded to type used here

        final static String VpnName = "VPN_NAME";
        final static String VpnGateway = "VPN_Gateway";
        final static String VpnUsername= "VPN_USERNAME";
        final static String VpnPassword = "VPN_PASSWORD";

        public VpnProfile(String name, String gateway, String username, String password){
            this.username = username;
            this.gateway = gateway;
            this.name = name;
            this.password = password;
            this.alias = username + "@" + gateway;
        }

        public Bundle toBundle(){
            Bundle b = new Bundle();
            b.putString(VpnName, name);
            b.putString(VpnGateway, gateway);
            b.putString(VpnUsername, username);
            b.putString(VpnPassword, password);
            return b;
        }

        public static VpnProfile fromBundle(Bundle b){
            String name = b.getString(VpnName);
            String gateway = b.getString(VpnGateway);
            String username = b.getString(VpnUsername);
            String password = b.getString(VpnPassword);
            if (name != null && gateway != null && username != null && password != null)
            {
                return new VpnProfile(name, gateway, username, password);
            }else{
                return null;
            }
        }

	@Override
	public String toString()
	{
		return name;
	}
	
	@Override
	public boolean equals(Object o)
	{
		if (o != null && o instanceof VpnProfile)
		{
			return this.name== ((VpnProfile)o).name;
		}
		return false;
	}

	@Override
	public VpnProfile clone()
	{
		try
		{
			return (VpnProfile)super.clone();
		}
		catch (CloneNotSupportedException e)
		{
			throw new AssertionError();
		}
	}
}
