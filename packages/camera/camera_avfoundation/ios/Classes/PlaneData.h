//
//  ImagePlaneData.h
//  Pods
//
//  Created by vhitesh more on 23/02/22.
//

@import Foundation;
@import Flutter;

@interface PlaneData : NSObject

@property (nonatomic, strong) NSNumber *width;
@property (nonatomic, strong) NSNumber *height;
@property (nonatomic, strong) NSNumber *bytesPerRow;

- (instancetype)initWithData:(NSNumber *)width height:(NSNumber *)height bytesPerRow:(NSNumber *)bytesPerRow;

@end