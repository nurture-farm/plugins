//
//  MLKVisionImage+FlutterPlugin.m
//  camera
//
//  Created by vhiteshmore on 26/01/22.
//

#import <Foundation/Foundation.h>
#import "CameraPlugin.h"

@implementation MLKVisionImage(FlutterPlugin)

+ (MLKVisionImage *)visionImageFromData:(NSData *)bytes
                                            planeData:(NSArray<PlaneData *> *)planeData
                                            width:(NSNumber *)width
                                            height:(NSNumber *)height
                                  format:(FourCharCode)format {
    NSData *imageBytes = bytes;
    
    size_t planeCount = planeData.count;
    
    CVPixelBufferRef pxBuffer = NULL;
    if (planeCount == 0) {
        @throw [NSException exceptionWithName:NSInvalidArgumentException
                                       reason:@"Can't create image buffer with 0 planes."
                                     userInfo:nil];
    } else if (planeCount == 1) {
        PlaneData *plane = planeData[0];
        NSNumber *bytesPerRow = [plane bytesPerRow];
        
        pxBuffer = [self bytesToPixelBuffer:width.unsignedLongValue
                                     height:height.unsignedLongValue
                                     format:format
                                baseAddress:(void *)imageBytes.bytes
                                bytesPerRow:bytesPerRow.unsignedLongValue];
    } else {
        pxBuffer = [self planarBytesToPixelBuffer:width.unsignedLongValue
                                           height:height.unsignedLongValue
                                           format:format
                                      baseAddress:(void *)imageBytes.bytes
                                         dataSize:imageBytes.length
                                       planeCount:planeCount
                                        planeData:planeData];
    }
    
    return [self pixelBufferToVisionImage:pxBuffer];
}

+ (CVPixelBufferRef)bytesToPixelBuffer:(size_t)width
                                height:(size_t)height
                                format:(FourCharCode)format
                           baseAddress:(void *)baseAddress
                           bytesPerRow:(size_t)bytesPerRow {
    CVPixelBufferRef pxBuffer = NULL;
    CVPixelBufferCreateWithBytes(kCFAllocatorDefault, width, height, format, baseAddress, bytesPerRow,
                                 NULL, NULL, NULL, &pxBuffer);
    return pxBuffer;
}

+ (CVPixelBufferRef)planarBytesToPixelBuffer:(size_t)width
                                      height:(size_t)height
                                      format:(FourCharCode)format
                                 baseAddress:(void *)baseAddress
                                    dataSize:(size_t)dataSize
                                  planeCount:(size_t)planeCount
                                   planeData:(NSArray *)planeData {
    size_t widths[planeCount];
    size_t heights[planeCount];
    size_t bytesPerRows[planeCount];
    
    void *baseAddresses[planeCount];
    baseAddresses[0] = baseAddress;
    
    size_t lastAddressIndex = 0;  // Used to get base address for each plane
    for (int i = 0; i < planeCount; i++) {
        PlaneData *plane = planeData[i];
        
        NSNumber *width = [plane width];
        NSNumber *height = [plane height];
        NSNumber *bytesPerRow = [plane bytesPerRow];
        
        widths[i] = width.unsignedLongValue;
        heights[i] = height.unsignedLongValue;
        bytesPerRows[i] = bytesPerRow.unsignedLongValue;
        
        if (i > 0) {
            size_t addressIndex = lastAddressIndex + heights[i - 1] * bytesPerRows[i - 1];
            baseAddresses[i] = baseAddress + addressIndex;
            lastAddressIndex = addressIndex;
        }
    }
    
    CVPixelBufferRef pxBuffer = NULL;
    CVPixelBufferCreateWithPlanarBytes(kCFAllocatorDefault, width, height, format, NULL, dataSize,
                                       planeCount, baseAddresses, widths, heights, bytesPerRows, NULL,
                                       NULL, NULL, &pxBuffer);
    
    return pxBuffer;
}

+ (MLKVisionImage *)pixelBufferToVisionImage:(CVPixelBufferRef)pixelBufferRef {
    CIImage *ciImage = [CIImage imageWithCVPixelBuffer:pixelBufferRef];
    
    CIContext *temporaryContext = [CIContext contextWithOptions:nil];
    CGImageRef videoImage =
    [temporaryContext createCGImage:ciImage
                           fromRect:CGRectMake(0, 0, CVPixelBufferGetWidth(pixelBufferRef),
                                               CVPixelBufferGetHeight(pixelBufferRef))];
    
    UIImage *uiImage = [UIImage imageWithCGImage:videoImage];
    CVPixelBufferRelease(pixelBufferRef);
    CGImageRelease(videoImage);
    return [[MLKVisionImage alloc] initWithImage:uiImage];
}

+ (NSDictionary *)barcodeToDictionary:(MLKBarcode *)barcode {
    NSMutableDictionary *dictionary = [NSMutableDictionary new];
    [dictionary addEntriesFromDictionary:@{
        @"type" : @(barcode.valueType) ?: [NSNull null],
        @"format" : @(barcode.format) ?: [NSNull null],
        @"rawValue" : barcode.rawValue ?: [NSNull null],
        @"rawBytes" : barcode.rawData ?: [NSNull null],
        @"displayValue" : barcode.displayValue ?: [NSNull null],
        @"boundingBoxLeft" : @(barcode.frame.origin.x),
        @"boundingBoxTop" : @(barcode.frame.origin.y),
        @"boundingBoxBottom" : @(barcode.frame.origin.y + barcode.frame.size.height),
        @"boundingBoxRight" : @(barcode.frame.origin.x + barcode.frame.size.width)
    }];
    
    switch (barcode.valueType) {
        case MLKBarcodeValueTypeUnknown:
        case MLKBarcodeValueTypeISBN:
        case MLKBarcodeValueTypeProduct:
        case MLKBarcodeValueTypeText:
            break;
        case MLKBarcodeValueTypeWiFi:
            [dictionary addEntriesFromDictionary:[self wifiToDictionary:barcode.wifi]];
            break;
        case MLKBarcodeValueTypeURL:
            [dictionary addEntriesFromDictionary:[self urlToDictionary:barcode.URL]];
            break;
        case MLKBarcodeValueTypeEmail:
            [dictionary addEntriesFromDictionary:[self emailToDictionary:barcode.email]];
            break;
        case MLKBarcodeValueTypePhone:
            [dictionary addEntriesFromDictionary:[self phoneToDictionary:barcode.phone]];
            break;
        case MLKBarcodeValueTypeSMS:
            [dictionary addEntriesFromDictionary:[self smsToDictionary:barcode.sms]];
            break;
        case MLKBarcodeValueTypeGeographicCoordinates:
            [dictionary addEntriesFromDictionary:[self geoPointToDictionary:barcode.geoPoint]];
            break;
        case MLKBarcodeValueTypeDriversLicense:
            [dictionary addEntriesFromDictionary:[self driverLicenseToDictionary:barcode.driverLicense]];
            break;
        case MLKBarcodeValueTypeContactInfo:
            [dictionary addEntriesFromDictionary:[self contactInfoToDictionary:barcode.contactInfo]];
            break;
        case MLKBarcodeValueTypeCalendarEvent:
            [dictionary addEntriesFromDictionary:[self calendarEventToDictionary:barcode.calendarEvent]];
            break;
    }
    
    return dictionary;
}

+ (NSDictionary *)wifiToDictionary:(MLKBarcodeWiFi *)wifi {
    return @{
        @"ssid" : wifi.ssid ?: [NSNull null],
        @"password" : wifi.password ?: [NSNull null],
        @"encryption" : @(wifi.type)
    };
}

+ (NSDictionary *)urlToDictionary:(MLKBarcodeURLBookmark *)url {
    return @{
        @"title" : url.title ?: [NSNull null],
        @"url" : url.url ?: [NSNull null]
    };
}

+ (NSDictionary *)emailToDictionary:(MLKBarcodeEmail *)email {
    return @{
        @"address" : email.address ?: [NSNull null],
        @"body" : email.body ?: [NSNull null],
        @"subject" : email.subject ?: [NSNull null],
        @"emailType" : @(email.type)
    };
}

+ (NSDictionary *)phoneToDictionary:(MLKBarcodePhone *)phone {
    return @{
        @"number" : phone.number ?: [NSNull null],
        @"phoneType" : @(phone.type)
    };
}

+ (NSDictionary *)smsToDictionary:(MLKBarcodeSMS *)sms {
    return @{
        @"number" : sms.phoneNumber ?: [NSNull null],
        @"message" : sms.message ?: [NSNull null]
    };
}

+ (NSDictionary *)geoPointToDictionary:(MLKBarcodeGeoPoint *)geo {
    return @{
        @"longitude" : @(geo.longitude),
        @"latitude" : @(geo.latitude)
    };
}

+ (NSDictionary *)driverLicenseToDictionary:(MLKBarcodeDriverLicense *)license {
    return @{
        @"firstName" : license.firstName ?: [NSNull null],
        @"middleName" : license.middleName ?: [NSNull null],
        @"lastName" : license.lastName ?: [NSNull null],
        @"gender" : license.gender ?: [NSNull null],
        @"addressCity" : license.addressCity ?: [NSNull null],
        @"addressStreet" : license.addressStreet ?: [NSNull null],
        @"addressState" : license.addressState ?: [NSNull null],
        @"addressZip" : license.addressZip ?: [NSNull null],
        @"birthDate" : license.birthDate ?: [NSNull null],
        @"documentType" : license.documentType ?: [NSNull null],
        @"licenseNumber" : license.licenseNumber ?: [NSNull null],
        @"expiryDate" : license.expiryDate ?: [NSNull null],
        @"issueDate" : license.issuingDate ?: [NSNull null],
        @"country" : license.issuingCountry ?: [NSNull null]
    };
}

+ (NSDictionary *)contactInfoToDictionary:(MLKBarcodeContactInfo *)contact {
    NSMutableArray<NSDictionary *> *addresses = [NSMutableArray array];
    [contact.addresses enumerateObjectsUsingBlock:^(MLKBarcodeAddress *_Nonnull address,
                                                    NSUInteger idx, BOOL *_Nonnull stop) {
        NSMutableArray<NSString *> *addressLines = [NSMutableArray array];
        [address.addressLines enumerateObjectsUsingBlock:^(NSString *_Nonnull addressLine,
                                                           NSUInteger idx, BOOL *_Nonnull stop) {
            [addressLines addObject:addressLine];
        }];
        [addresses addObject:@{@"addressLines" : addressLines, @"addressType" : @(address.type)}];
    }];
    
    NSMutableArray<NSDictionary *> *emails = [NSMutableArray array];
    [contact.emails enumerateObjectsUsingBlock:^(MLKBarcodeEmail *_Nonnull email,
                                                 NSUInteger idx, BOOL *_Nonnull stop) {
        [emails addObject:@{
            @"address" : email.address ?: [NSNull null],
            @"body" : email.body ?: [NSNull null],
            @"subject" : email.subject ?: [NSNull null],
            @"emailType" : @(email.type)
        }];
    }];
    
    NSMutableArray<NSDictionary *> *phones = [NSMutableArray array];
    [contact.phones enumerateObjectsUsingBlock:^(MLKBarcodePhone *_Nonnull phone,
                                                 NSUInteger idx, BOOL *_Nonnull stop) {
        [phones addObject:@{@"number" : phone.number ?: [NSNull null], @"phoneType" : @(phone.type)}];
    }];
    
    NSMutableArray<NSString *> *urls = [NSMutableArray array];
    [contact.urls
     enumerateObjectsUsingBlock:^(NSString *_Nonnull url, NSUInteger idx, BOOL *_Nonnull stop) {
        [urls addObject:url];
    }];
    return @{
        @"addresses" : addresses,
        @"emails" : emails,
        @"phones" : phones,
        @"urls" : urls,
        @"formattedName" : contact.name.formattedName ?: [NSNull null],
        @"firstName" : contact.name.first ?: [NSNull null],
        @"lastName" : contact.name.last ?: [NSNull null],
        @"middleName" : contact.name.middle ?: [NSNull null],
        @"prefix" : contact.name.prefix ?: [NSNull null],
        @"pronunciation" : contact.name.pronunciation ?: [NSNull null],
        @"suffix" : contact.name.suffix ?: [NSNull null],
        @"jobTitle" : contact.jobTitle ?: [NSNull null],
        @"organization" : contact.organization ?: [NSNull null]
    };
}

+ (NSDictionary *)calendarEventToDictionary:(MLKBarcodeCalendarEvent *)calendar {
    return @{
        @"description" : calendar.eventDescription ?: [NSNull null],
        @"location" : calendar.location ?: [NSNull null],
        @"organizer" : calendar.organizer ?: [NSNull null],
        @"status" : calendar.status ?: [NSNull null],
        @"summary" : calendar.summary ?: [NSNull null],
        @"start" : @(calendar.start.timeIntervalSince1970),
        @"end" : @(calendar.end.timeIntervalSince1970)
    };
}

@end

