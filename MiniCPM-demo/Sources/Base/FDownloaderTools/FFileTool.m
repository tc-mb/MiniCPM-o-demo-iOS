//
//  FFileTool.m
//  FDownLoadDemo
//
//  Created by allison on 2018/8/18.
//  Copyright © 2018年 allison. All rights reserved.
//

#import "FFileTool.h"

@implementation FFileTool

+(BOOL)fileExists:(NSString *)filePath {
    if (filePath.length == 0) {
        return NO;
    }
    return [[NSFileManager defaultManager]fileExistsAtPath:filePath];
}

+ (long long)fileSize:(NSString *)filePath {
    if (![self fileExists:filePath]) {
        return 0;
    }
    NSDictionary *fileInfo = [[NSFileManager defaultManager]attributesOfItemAtPath:filePath error:nil];
    return [fileInfo[NSFileSize]longLongValue];
}

+ (void)moveFile:(NSString *)fromPath toPath:(NSString *)toPath {
    if (![self fileSize:fromPath]) {
        return;
    }
    [[NSFileManager defaultManager]moveItemAtPath:fromPath toPath:toPath error:nil];
}

+ (void)removeFile:(NSString *)filePath {
    [[NSFileManager defaultManager]removeItemAtPath:filePath error:nil];
}



@end
