//
//  VPNManager.js
//


var exec = require('cordova/exec');

module.exports = {
    isVpnCapable: function(success, error, options) {
        cordova.exec(success, error, "VPNManager", "isVpnCapable", [options]);
    },
    needsProfile: function(success, error, options) {
        cordova.exec(success, error, "VPNManager", "needsProfile", [options]);
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
    },
    registerCallback: function(success, error, options) {
        cordova.exec(success, error, "VPNManager", "registerCallback", [options]);
    },
    unregisterCallback: function(success, error, options) {
        cordova.exec(success, error, "VPNManager", "unregisterCallback", [options]);
    }
};
