//
//  ImagePlaneData.m
//  Pods
//
//  Created by vhitesh more on 23/02/22.
//

#import "PlaneData.h"

@implementation PlaneData

- (instancetype)initWithData:(NSNumber *)width height:(NSNumber *)height bytesPerRow:(NSNumber *)bytesPerRow {
    self = [super init];
    if (self) {
      _width = width;
      _height = height;
      _bytesPerRow = bytesPerRow;
    }

    return self;
}

@end