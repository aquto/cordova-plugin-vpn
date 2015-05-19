#import <Foundation/Foundation.h>
#import <NetworkExtension/NEVPNManager.h>
#import <NetworkExtension/NEVPNConnection.h>
#import <NetworkExtension/NEVPNProtocolIPSec.h>
#import <NetworkExtension/NEOnDemandRule.h>
#import <Security/Security.h>

#import "VPNManager.h"
#import "Reachability.h"
#import "UICKeyChainStore.h"
#import <Cordova/CDV.h>


@interface VPNManager () {
    NEVPNManager *vpnManager;
    UICKeyChainStore *store;
}

@end

@implementation VPNManager

static NSString * serviceName;

static BOOL allowWiFi;

- (void)pluginInitialize {
    serviceName = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleIdentifier"];
    allowWiFi = [[[NSBundle mainBundle] objectForInfoDictionaryKey:@"AllowWiFi"] boolValue];
    vpnManager = [NEVPNManager sharedManager];
    store = [UICKeyChainStore keyChainStoreWithService:serviceName];

    [vpnManager loadFromPreferencesWithCompletionHandler:^(NSError *error) {
        if(error)
            NSLog(@"Load error: %@", error);
        else if(vpnManager.protocol) {
            NEVPNProtocolIPSec *proto = (NEVPNProtocolIPSec *)vpnManager.protocol;
            proto.passwordReference = [self searchKeychainCopyMatching:@"VPNPassword"];
            proto.identityDataPassword = [store stringForKey:@"VPNCertPassword"];
            [vpnManager setProtocol:proto];
            [vpnManager setEnabled:YES];
            [vpnManager saveToPreferencesWithCompletionHandler:^(NSError *error) {
                if(error)
                    NSLog(@"Save config failed [%@]", error.localizedDescription);
                else
                    [self dumpConfig];
            }];
        }
    }];
}

- (void)registerCallback:(CDVInvokedUrlCommand*)command {
    NSString* localCallbackId = command.callbackId;
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [[NSNotificationCenter defaultCenter] addObserverForName:NEVPNStatusDidChangeNotification object:nil queue:[NSOperationQueue mainQueue] usingBlock:^(NSNotification *notif) {
        CDVPluginResult* pluginResult = [self vpnStatusToResult:vpnManager.connection.status];
        [pluginResult setKeepCallback:[NSNumber numberWithBool:YES]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:localCallbackId];
        NSLog (@"Successfully received VPN status change notification: %d", vpnManager.connection.status);
    }];
}

- (void)unregisterCallback:(CDVInvokedUrlCommand*)command {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

- (void)enable:(CDVInvokedUrlCommand*)command {
    NSString* localCallbackId = command.callbackId;

    [self.commandDelegate runInBackground:^{
        Reachability *reachability = [Reachability reachabilityForInternetConnection];
        NetworkStatus status = [reachability currentReachabilityStatus];
        if(!allowWiFi && status == ReachableViaWiFi) {
            NSLog(@"Failed to enable the Kickbit VPN because WiFi is enabled.");
            [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR] callbackId:localCallbackId];
        } else if(vpnManager.connection.status != NEVPNStatusDisconnected) {
            NSLog(@"Failed to enable the Kickbit VPN because the vpn is already enabled.");
            [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR] callbackId:localCallbackId];
        } else {
            NSLog(@"Enabling the Kickbit VPN.");
            NSError *startError;
            [vpnManager.connection startVPNTunnelAndReturnError:&startError];
            if(startError) {
                NSLog(@"Start error: %@", startError.localizedDescription);
                [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR] callbackId:localCallbackId];
            } else
                [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"CONNECTING"] callbackId:localCallbackId];
        }
    }];
}

- (void)disable:(CDVInvokedUrlCommand*)command {
    NSString* localCallbackId = command.callbackId;

    [self.commandDelegate runInBackground:^{
        CDVPluginResult* pluginResult = nil;
        
        if(vpnManager.connection.status != NEVPNStatusConnected)
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR];
        else {
            NSLog(@"Disabling the Kickbit VPN.");
            [vpnManager.connection stopVPNTunnel];
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"DISABLED"];
        }
        [self.commandDelegate sendPluginResult:pluginResult callbackId:localCallbackId];
        
    }];
}

- (CDVPluginResult *) vpnStatusToResult:(NEVPNStatus)status {
    CDVPluginResult *result = nil;

    switch(status) {
        case NEVPNStatusInvalid:
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR];
            break;
        case NEVPNStatusDisconnected:
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"DISCONNECTED"];
            break;
        case NEVPNStatusConnecting:
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"CONNECTING"];
            break;
        case NEVPNStatusConnected:
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"CONNECTED"];
            break;
        case NEVPNStatusReasserting:
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"CONNECTING"];
            break;
        case NEVPNStatusDisconnecting:
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"DISCONNECTING"];
            break;
        default:
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR];
    }

    return result;
}

- (void)provision:(CDVInvokedUrlCommand*)command {
    NSMutableDictionary* options = [command.arguments objectAtIndex:0];
    NSString* localCallbackId = command.callbackId;

    [self.commandDelegate runInBackground:^{
        NSLog(@"Provisioning the Kickbit VPN");

        NSString* vpnUsername = [options objectForKey:@"vpnUsername"];
        NSString* vpnPassword = [options objectForKey:@"vpnPassword"];
        NSString* vpnHost = [options objectForKey:@"vpnHost"];
        NSString* vpnCert = [options objectForKey:@"userCertificate"];
        NSString* vpnCertPassword = [options objectForKey:@"userCertificatePassword"];
        NSString* appName = [options objectForKey:@"appName"];

        NSData* certData = [[NSData alloc]initWithBase64EncodedString:vpnCert options:NSDataBase64DecodingIgnoreUnknownCharacters];
        [store setString:vpnPassword forKey:@"VPNPassword"];
        [store setString:vpnCertPassword forKey:@"VPNCertPassword"];
        [store setData:certData forKey:@"VPNCert"];
        [store synchronize];
        [vpnManager loadFromPreferencesWithCompletionHandler:^(NSError *error) {
            __block CDVPluginResult* pluginResult = nil;

            if(error) {
                NSLog(@"Load error: %@", error);
                [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR] callbackId:localCallbackId];
            } else {
                NEVPNProtocolIPSec *proto = [[NEVPNProtocolIPSec alloc] init];
                proto.username = vpnUsername;
                proto.passwordReference = [self searchKeychainCopyMatching:@"VPNPassword"];
                proto.serverAddress = vpnHost;
                proto.authenticationMethod = NEVPNIKEAuthenticationMethodCertificate;
                proto.identityData = certData;
                proto.identityDataPassword = vpnCertPassword;
                proto.localIdentifier = [NSString stringWithFormat:@"%@@%@", vpnUsername, vpnHost];
                proto.remoteIdentifier = vpnHost;
                proto.useExtendedAuthentication = YES;
                proto.disconnectOnSleep = NO;
                [vpnManager setLocalizedDescription:appName];
                [vpnManager setProtocol:proto];
                [vpnManager setEnabled:YES];
                if(!allowWiFi) {
                    [vpnManager setOnDemandEnabled:YES];
                    NSMutableArray *rules = [[NSMutableArray alloc] init];
                    NEOnDemandRuleDisconnect *disconnectRule = [NEOnDemandRuleDisconnect new];
                    disconnectRule.interfaceTypeMatch = NEOnDemandRuleInterfaceTypeWiFi;
                    [rules addObject:disconnectRule];
                    [[NEVPNManager sharedManager] setOnDemandRules:rules];
                }
                [vpnManager saveToPreferencesWithCompletionHandler:^(NSError *error) {
                    if(error) {
                        NSLog(@"Save config failed [%@]", error.localizedDescription);
                        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:NO];
                    } else {
                        [self dumpConfig];
                        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:YES];
                    }
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:localCallbackId];
                }];
            }
        }];
    }];
}

- (void)status:(CDVInvokedUrlCommand*)command {
    NSString* localCallbackId = command.callbackId;

    [self.commandDelegate runInBackground:^{
        CDVPluginResult* pluginResult = [self vpnStatusToResult:vpnManager.connection.status];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:localCallbackId];
    }];
}

- (void)needsProfile:(CDVInvokedUrlCommand*)command {
    NSMutableDictionary* options = [command.arguments objectAtIndex:0];
    NSString* localCallbackId = command.callbackId;

    [self.commandDelegate runInBackground:^{
        NSString* vpnUsername = [options objectForKey:@"vpnUsername"];
        NSString* vpnPassword = [options objectForKey:@"vpnPassword"];
        NSString* vpnHost = [options objectForKey:@"vpnHost"];
        NSString* vpnCert = [options objectForKey:@"userCertificate"];
        NSString* vpnCertPassword = [options objectForKey:@"userCertificatePassword"];
        if (vpnUsername != nil && vpnHost != nil && vpnCert != nil && vpnCertPassword != nil && vpnPassword != nil) {
            [vpnManager loadFromPreferencesWithCompletionHandler:^(NSError *error) {
                if(error)
                    [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR] callbackId:localCallbackId];
                else {
                    NEVPNProtocolIPSec *proto = (NEVPNProtocolIPSec *)vpnManager.protocol;
                    NSString* passwdCmp = [store stringForKey:@"VPNPassword"];
                    NSString* certPasswdCmp = [store stringForKey:@"VPNCertPassword"];
                    NSData* certDataCmp = [store dataForKey:@"VPNCert"];
                    NSData* certData = [[NSData alloc]initWithBase64EncodedString:vpnCert options:NSDataBase64DecodingIgnoreUnknownCharacters];
                    NSLog(@"Username: %@", [proto.username isEqualToString:vpnUsername] ? @"YES" : @"NO");
                    NSLog(@"Server Address: %@", [proto.serverAddress isEqualToString:vpnHost] ? @"YES" : @"NO");
                    NSLog(@"Certificate: %@", [certDataCmp isEqualToData:certData] ? @"YES" : @"NO");
                    NSLog(@"Certificate Password: %@", [certPasswdCmp isEqualToString:vpnCertPassword] ? @"YES" : @"NO");
                    NSLog(@"Password: %@", [passwdCmp isEqualToString:vpnPassword] ? @"YES" : @"NO");
                    if (proto && [proto.username isEqualToString:vpnUsername] && [proto.serverAddress isEqualToString:vpnHost] &&
                        [certDataCmp isEqualToData:certData] && [certPasswdCmp isEqualToString:vpnCertPassword] && [passwdCmp isEqualToString:vpnPassword]) {
                        proto.passwordReference = [self searchKeychainCopyMatching:@"VPNPassword"];
                        proto.identityDataPassword = [store stringForKey:@"VPNCertPassword"];
                        [vpnManager setProtocol:proto];
                        [vpnManager setEnabled:YES];
                        [vpnManager saveToPreferencesWithCompletionHandler:^(NSError *error) {
                            CDVPluginResult* pluginResult = nil;
                            if(error) {
                                NSLog(@"Save config failed [%@]", error.localizedDescription);
                                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR];
                            } else
                                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:NO];
                            [self.commandDelegate sendPluginResult:pluginResult callbackId:localCallbackId];
                        }];
                    } else {
                        [store removeItemForKey:@"VPNPassword"];
                        [store removeItemForKey:@"VPNCertPassword"];
                        [store removeItemForKey:@"VPNCert"];
                        [store synchronize];
                        [vpnManager removeFromPreferencesWithCompletionHandler:^(NSError *error) {
                            if(error)
                                NSLog(@"Remove config failed [%@]", error.localizedDescription);
                            [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:YES] callbackId:localCallbackId];
                        }];
                    }
                }
            }];
        } else
            [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR] callbackId:localCallbackId];
    }];
}

- (void)dumpConfig {
    NSLog(@"description: %@", vpnManager.localizedDescription);
    NEVPNProtocolIPSec *proto = (NEVPNProtocolIPSec *)vpnManager.protocol;
    NSLog(@"username: %@", proto.username);
    NSLog(@"passwordReference: %@", proto.passwordReference);
    NSLog(@"serverAddress: %@", proto.serverAddress);
    NSLog(@"authenticationMethod: %d", proto.authenticationMethod);
    NSLog(@"identityData: %@", proto.identityData);
    NSLog(@"identityDataPassword: %@", proto.identityDataPassword);
    NSLog(@"localIdentifier: %@", proto.localIdentifier);
    NSLog(@"remoteIdentifier: %@", proto.remoteIdentifier);
    NSLog(@"useExtendedAuthentication: %d", proto.useExtendedAuthentication);
    NSLog(@"disconnectOnSleep: %d", proto.disconnectOnSleep);
}

- (NSData *)searchKeychainCopyMatching:(NSString *)identifier {
    NSMutableDictionary *searchDictionary = [[NSMutableDictionary alloc] init];
    
    NSData *encodedIdentifier = [identifier dataUsingEncoding:NSUTF8StringEncoding];
    
    searchDictionary[(__bridge id)kSecClass] = (__bridge id)kSecClassGenericPassword;
    searchDictionary[(__bridge id)kSecAttrGeneric] = encodedIdentifier;
    searchDictionary[(__bridge id)kSecAttrAccount] = encodedIdentifier;
    searchDictionary[(__bridge id)kSecAttrService] = serviceName;
    
    searchDictionary[(__bridge id)kSecMatchLimit] = (__bridge id)kSecMatchLimitOne;
    searchDictionary[(__bridge id)kSecReturnPersistentRef] = @YES;
    
    CFTypeRef result = NULL;
    SecItemCopyMatching((__bridge CFDictionaryRef)searchDictionary, &result);
    
    return (__bridge_transfer NSData *)result;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

@end