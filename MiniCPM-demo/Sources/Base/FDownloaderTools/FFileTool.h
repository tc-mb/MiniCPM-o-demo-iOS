//
//  FFileTool.h
//  FDownLoadDemo
//
//  Created by allison on 2018/8/18.
//  Copyright © 2018年 allison. All rights reserved.
//

#import <Foundation/Foundation.h>

@interface FFileTool : NSObject

+ (BOOL)fileExists:(NSString *)filePath;
+ (long long)fileSize:(NSString *)filePath;
+ (void)moveFile:(NSString *)fromPath toPath:(NSString *)toPath;
+ (void)removeFile:(NSString *)filePath;

@end
