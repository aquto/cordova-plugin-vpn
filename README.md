cordova-plugin-vpn
======================

IOS
---

Android
-------
Exposes 5 methods: (android only)

+ isVpnCapable: returns true if this device is capable of establishing a vpn connection, else false
+ isUp: return true if vpn connection is active, else false
+ listen: register listeners for state and error state updates
    + possible states: DISABLED, CONNECTING, CONNECTED, DISCONNECTING
    + possible error states: NO\_ERROR, AUTH\_FAILED, PEER\_AUTH\_FAILED, LOOKUP\_FAILED, UNREACHABLE, GENERIC\_ERROR, DISALLOWED\_NETWORK\_TYPE
+ enable: establish a new VPN connection using the provided provisioning json. Will return an error if wifi/wimax/ethernet connection is active, and will shutdown the VPN if a connection with one of those types becomes active
+ disable: terminate the currently active VPN connection

note: return in this context means calling the provided success callback function. Returning an error means calling the provided error callback function

Error codes used when calling the error callback function:

+ NOT/_SUPPORTED,
+ MISSING/_FIELDS,
+ UNKNOWN/_ERROR,
+ PERMISSION/_NOT/_GRANTED,
+ DISALLOWED/_NETWORK/_TYPE

Edit this configuration file to enable or disable mobileOnly (disallow VPN connection while connected to wifi, wimax or ethernet) and to set the vpn name as shown by the VPN system dialog.
src/android/vpn_plugin_config.xml
