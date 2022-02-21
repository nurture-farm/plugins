// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#import <Flutter/Flutter.h>
#import <MLKitVision/MLKitVision.h>
#import <MLKitBarcodeScanning/MLKitBarcodeScanning.h>

@interface CameraPlugin : NSObject <FlutterPlugin>
@end

@interface PlaneData : NSObject
@property (nonatomic, strong) NSNumber *width;
@property (nonatomic, strong) NSNumber *height;
@property (nonatomic, strong) NSNumber *bytesPerRow;
@end

@interface MLKVisionImage(FlutterPlugin)
+ (MLKVisionImage *)visionImageFromData:(NSData *)bytes
                                            planeData:(NSArray<PlaneData *> *)planeData
                                            width:(NSNumber *)width
                                            height:(NSNumber *)height
                                            format:(FourCharCode)format;
+ (NSDictionary *)barcodeToDictionary:(MLKBarcode *)barcode;
@end


