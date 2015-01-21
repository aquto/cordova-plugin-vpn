#import <Cordova/CDV.h>

@interface VPNManager : CDVPlugin

- (void)enable:(CDVInvokedUrlCommand*)command;
- (void)disable:(CDVInvokedUrlCommand*)command;
- (void)provision:(CDVInvokedUrlCommand*)command;
- (void)status:(CDVInvokedUrlCommand*)command;
- (void)dealloc;

@end