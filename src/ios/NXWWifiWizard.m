#import "NXWWifiWizard.h"
#import <SystemConfiguration/CaptiveNetwork.h>
#import <NetworkExtension/NetworkExtension.h>

static NSString * const GizNetworkInfoArchiveKey = @"GizNetworkInfoArchiveKey";

typedef NS_ENUM(NSInteger, GizNetworkEncryptionType) {
    GizNetworkEncryptionNone,
    GizNetworkEncryptionWPA,
    GizNetworkEncryptionWEP,
    GizNetworkEncryptionOther
};


@interface NSString (GizCustom)

/// 由于 cordova js 传过来的字符串首尾都有双引号 “” (安卓需要拼上去，但对于iOS来说是多余的)，
/// 要去掉多余的引号。
@property (nonatomic, strong, readonly) NSString *trimString;

@end

@implementation NSString (GizCustom)

- (NSString *)trimString {
    
    NSMutableString *tempString = [self mutableCopy];
    
    if ([tempString hasPrefix:@"\""]) {
        [tempString deleteCharactersInRange:NSMakeRange(0, 1)];
    }
    
    if ([tempString hasSuffix:@"\""]) {
        [tempString deleteCharactersInRange:NSMakeRange(tempString.length-1, 1)];
    }
    
    return tempString;
}

@end


@implementation NXWWifiWizard

- (id)fetchSSIDInfo {
    // see http://stackoverflow.com/a/5198968/907720
    NSArray *ifs = (__bridge_transfer NSArray *)CNCopySupportedInterfaces();
    NSLog(@"Supported interfaces: %@", ifs);
    NSDictionary *info;
    for (NSString *ifnam in ifs) {
        info = (__bridge_transfer NSDictionary *)CNCopyCurrentNetworkInfo((__bridge CFStringRef)ifnam);
        NSLog(@"%@ => %@", ifnam, info);
        if (info && [info count]) { break; }
    }
    return info;
}

- (NSString *)getCurrentWiFiSSID {
    NSDictionary *r = [self fetchSSIDInfo];
    
    NSString *ssid = [r objectForKey:(id)kCNNetworkInfoKeySSID];
    
    if (ssid && ssid.length > 0) {
        return ssid;
    }
    
    return @"";
}

- (void)addNetwork:(CDVInvokedUrlCommand *)command {
    CDVPluginResult *pluginResult = nil;
    
    if (command.arguments.count == 0) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Invalid Wi-Fi SSID."];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }
    
    NSString *ssid = @"";
    NSString *password = @"";
    GizNetworkEncryptionType encryption = GizNetworkEncryptionNone;
    
    switch (command.arguments.count) {
        case 1:
        case 2:
            ssid = command.arguments[0];
            break;
        case 3: {
            ssid = command.arguments[0];
            NSString *type = command.arguments[1];
            
            if ([type isEqualToString:@"WPA"]) {
                encryption = GizNetworkEncryptionWPA;
                password = command.arguments[2];
            } else if ([type isEqualToString:@"WEP"]) {
                encryption = GizNetworkEncryptionWEP;
                password = command.arguments[2];
            }
        }
            break;
            
        default:
            break;
    }
    
    NSDictionary *networkInfo = @{@"SSID": ssid.trimString, @"password": password.trimString, @"encryption": @(encryption)};
    
    if ([self archiveNetworkInfo:networkInfo]) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Invalid Network Infomation."];
    }
    
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)removeNetwork:(CDVInvokedUrlCommand *)command {
    CDVPluginResult *pluginResult = nil;
    
    if (command.arguments.count == 0) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Invalid Wi-Fi SSID."];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }
    
    NSString *ssid = ((NSString *)command.arguments[0]).trimString;
    [self removeNetworkInfoForSSID:ssid];
    
    if (@available(iOS 11.0, *)) {
        [[NEHotspotConfigurationManager sharedManager] removeConfigurationForSSID:ssid];
    }
    
    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)connectNetwork:(CDVInvokedUrlCommand *)command {
    CDVPluginResult *pluginResult = nil;
    
    if (@available(iOS 11.0, *)) {
        
        if (command.arguments.count == 0) {
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Invalid Wi-Fi SSID."];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            return;
        }
        
        NSString *ssid = ((NSString *)command.arguments[0]).trimString;
        NSDictionary *networkInfo = [self networkInfoForSSID:ssid];
        
        if (!networkInfo) {
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[NSString stringWithFormat:@"Wi-Fi %@ is not added.", ssid]];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            return;
        }
        
        NSString *password = networkInfo[@"password"];
        GizNetworkEncryptionType encryption = [networkInfo[@"encryption"] integerValue];
        
        NSLog(@"ssid: %@, password: %@, encryption: %@", ssid, password, @(encryption));
        
        NEHotspotConfiguration *configuration = [[NEHotspotConfiguration alloc] initWithSSID:ssid passphrase:password isWEP:(encryption == GizNetworkEncryptionWEP ? YES : NO)];
        
        [[NEHotspotConfigurationManager sharedManager] applyConfiguration:configuration completionHandler:^(NSError * _Nullable error) {
            CDVPluginResult *pluginResult = nil;
            
            if ([[self getCurrentWiFiSSID] isEqualToString:ssid]) {
                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
            } else if (error) {
                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.localizedDescription];
            } else {
                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Unknown error."];
            }
        }];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"This method does not support the system version less than iOS 11.0"];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

- (void)disconnectNetwork:(CDVInvokedUrlCommand *)command {
    
    CDVPluginResult *pluginResult = nil;
    
    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Not supported"];
    
    [self.commandDelegate sendPluginResult:pluginResult
                                callbackId:command.callbackId];
}

- (void)listNetworks:(CDVInvokedUrlCommand *)command {
    
    if (@available(iOS 11.0, *)) {
        
        [[NEHotspotConfigurationManager sharedManager] getConfiguredSSIDsWithCompletionHandler:^(NSArray<NSString *> *ssidArray) {
            
            NSArray<NSString *> *ssids;
            
            if (ssidArray) {
                ssids = [NSArray arrayWithArray:ssidArray];
            } else {
                ssids = @[];
            }
            
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsArray:ssids];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        }];
        
    } else {
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"This method does not support the system version less than iOS 11.0"];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

- (void)getScanResults:(CDVInvokedUrlCommand *)command {
    CDVPluginResult *pluginResult = nil;
    
    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Not supported"];
    
    [self.commandDelegate sendPluginResult:pluginResult
                                callbackId:command.callbackId];
}

- (void)startScan:(CDVInvokedUrlCommand *)command {
    CDVPluginResult *pluginResult = nil;

    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Not supported"];

    [self.commandDelegate sendPluginResult:pluginResult
                                callbackId:command.callbackId];
}

- (void)disconnect:(CDVInvokedUrlCommand *)command {
    CDVPluginResult *pluginResult = nil;
    
    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Not supported"];
    
    [self.commandDelegate sendPluginResult:pluginResult
                                callbackId:command.callbackId];
}

- (void)getConnectedSSID:(CDVInvokedUrlCommand *)command {
    CDVPluginResult *pluginResult = nil;
    NSDictionary *r = [self fetchSSIDInfo];

    NSString *ssid = [r objectForKey:(id)kCNNetworkInfoKeySSID]; //@"SSID"

    if (ssid && [ssid length]) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:ssid];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Not available"];
    }

    [self.commandDelegate sendPluginResult:pluginResult
                                callbackId:command.callbackId];
}

- (void)getConnectedSSIDWithpermission:(CDVInvokedUrlCommand*)command {
    CDVPluginResult *pluginResult = nil;
    NSDictionary *r = [self fetchSSIDInfo];
    
    NSString *ssid = [r objectForKey:(id)kCNNetworkInfoKeySSID]; //@"SSID"
    
    if (ssid && [ssid length]) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:ssid];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Not available"];
    }
    
    [self.commandDelegate sendPluginResult:pluginResult
                                callbackId:command.callbackId];
}

- (void)getConnectedBSSID:(CDVInvokedUrlCommand*)command {
    CDVPluginResult *pluginResult = nil;
    NSDictionary *r = [self fetchSSIDInfo];
    
    NSString *bssid = [r objectForKey:(id)kCNNetworkInfoKeyBSSID]; //@"SSID"
    
    if (bssid && [bssid length]) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:bssid];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Not available"];
    }
    
    [self.commandDelegate sendPluginResult:pluginResult
                                callbackId:command.callbackId];
}
- (void)isWifiEnabled:(CDVInvokedUrlCommand *)command {
    
}

- (void)setWifiEnabled:(CDVInvokedUrlCommand *)command {
    CDVPluginResult *pluginResult = nil;

    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Not supported"];

    [self.commandDelegate sendPluginResult:pluginResult
                                callbackId:command.callbackId];
}

- (void)is5g:(CDVInvokedUrlCommand *)command {
    CDVPluginResult *pluginResult = nil;

    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Not supported"];

    [self.commandDelegate sendPluginResult:pluginResult
                                callbackId:command.callbackId];
}

#pragma mark - Archive methods

/**
 归档存储网络信息。

 @param networkInfo 网络信息。格式 {"SSID": "Gizwits", "password": "12345678", "encryption": "0"}
                    其中 encryption 的类型为 GizNetworkEncryptionType
 */
- (BOOL)archiveNetworkInfo:(NSDictionary *)networkInfo {
    
    if (!networkInfo || networkInfo.count == 0) {
        return NO;
    }
    
    NSString *ssid = networkInfo[@"SSID"];
    
    if (!ssid || ssid.length == 0) {
        return NO;
    }
    
    NSUserDefaults *userDefaults = [NSUserDefaults standardUserDefaults];
    NSDictionary *infoDict = [userDefaults objectForKey:GizNetworkInfoArchiveKey];
    
    NSMutableDictionary *mutableInfoDict;
    
    if (!infoDict) {
        mutableInfoDict = [[NSMutableDictionary alloc] init];
    } else {
        mutableInfoDict = [NSMutableDictionary dictionaryWithDictionary:infoDict];
    }
    
    [mutableInfoDict setObject:networkInfo forKey:ssid];
    [userDefaults setObject:mutableInfoDict forKey:GizNetworkInfoArchiveKey];
    [userDefaults synchronize];
    
    return YES;
}

- (NSDictionary *)networkInfoForSSID:(NSString *)ssid {
    
    if (!ssid || ssid.length == 0) {
        return nil;
    }
    
    NSUserDefaults *userDefaults = [NSUserDefaults standardUserDefaults];
    NSDictionary *infoDict = [userDefaults objectForKey:GizNetworkInfoArchiveKey];
    
    if (!infoDict) {
        return nil;
    }
    
    return infoDict[ssid];
}

- (BOOL)removeNetworkInfoForSSID:(NSString *)ssid {
    if (!ssid || ssid.length == 0) {
        return NO;
    }
    
    NSUserDefaults *userDefaults = [NSUserDefaults standardUserDefaults];
    NSDictionary *infoDict = [userDefaults objectForKey:GizNetworkInfoArchiveKey];
    
    if (!infoDict) {
        return NO;
    }
    
    NSMutableDictionary *mutableInfoDict = [NSMutableDictionary dictionaryWithDictionary:infoDict];
    
    [mutableInfoDict removeObjectForKey:ssid];
    [userDefaults setObject:mutableInfoDict forKey:GizNetworkInfoArchiveKey];
    [userDefaults synchronize];
    
    return YES;
}

@end
