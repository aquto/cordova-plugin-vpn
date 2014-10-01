//
//  VPNManager.js
//


var exec = require('cordova/exec');

module.exports = {
    isVpnCapable: function(success, error, options) {
        cordova.exec(success, error, "VPNManager", "isVpnCapable", [options]);
    },
    isUp: function(success, error, options) {
        cordova.exec(success, error, "VPNManager", "isUp", [options]);
    },
    // register functions to be called when the vpn connection's state or error state change
    // possible states: DISABLED, CONNECTING, CONNECTED, DISCONNECTING,
    // possible error states: NO_ERROR, AUTH_FAILED, PEER_AUTH_FAILED, LOOKUP_FAILED, UNREACHABLE, GENERIC_ERROR, DISALLOWED_NETWORK_TYPE
    listen: function(onStateChange, onErrorStateChange, options) {
        cordova.exec(onStateChange, onErrorStateChange, "VPNManager", "listen", [options]);
    },
    // attempt to start the vpn
    enable: function(success, error, options) {
        cordova.exec(success, error, "VPNManager", "enable", [options]);
    },
    // attempt to disable the vpn
    disable: function(success, error, options) {
        cordova.exec(success, error, "VPNManager", "disable", [options]);
    },
    provision: function(success, error, options) {
        cordova.exec(success, error, "VPNManager", "provision", [options]);
    },
    status: function(success, error, options) {
        cordova.exec(success, error, "VPNManager", "status", [options]);
    }
};
