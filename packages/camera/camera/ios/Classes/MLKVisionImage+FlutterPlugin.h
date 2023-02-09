//
//  MLKVisionImage+FlutterPlugin.h
//  camera
//
//  Created by vhitesh more on 23/02/22.
//

#import <Foundation/Foundation.h>
#import <Flutter/Flutter.h>
#import <MLKitVision/MLKitVision.h>
#import <MLKitBarcodeScanning/MLKitBarcodeScanning.h>
#import "PlaneData.h"

@interface MLKVisionImage(FlutterPlugin)
+ (MLKVisionImage *)visionImageFromData:(NSData *)bytes
                                            planeData:(NSArray<PlaneData *> *)planeData
                                            width:(NSNumber *)width
                                            height:(NSNumber *)height
                                            format:(FourCharCode)format;
+ (NSDictionary *)barcodeToDictionary:(MLKBarcode *)barcode;
@end