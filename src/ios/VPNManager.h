#import <Cordova/CDV.h>

@interface VPNManager : CDVPlugin

- (void)pluginInitialize;
- (void)registerCallback:(CDVInvokedUrlCommand*)command;
- (void)unregisterCallback:(CDVInvokedUrlCommand*)command;
- (void)enable:(CDVInvokedUrlCommand*)command;
- (void)disable:(CDVInvokedUrlCommand*)command;
- (void)provision:(CDVInvokedUrlCommand*)command;
- (void)status:(CDVInvokedUrlCommand*)command;
- (void)needsProfile:(CDVInvokedUrlCommand*)command;
- (void)dealloc;

@end