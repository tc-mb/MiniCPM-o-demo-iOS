//
//  FDownLoaderManager.m
//  FDownLoadDemo
//
//  Created by allison on 2018/8/25.
//  Copyright © 2018年 allison. All rights reserved.
//

#import "FDownLoaderManager.h"
#import "NSString+MD5.h"

@interface FDownLoaderManager() <NSCopying,NSMutableCopying>

@property (nonatomic, strong) NSMutableDictionary *downLoaderInfo;

@end

@implementation FDownLoaderManager

static FDownLoaderManager *_shareInstance;

+ (instancetype)shareInstance {
    if (_shareInstance == nil) {
        _shareInstance = [[self alloc] init];
    }
    return _shareInstance;
}

+ (instancetype)allocWithZone:(struct _NSZone *)zone {
    if (!_shareInstance) {
        static dispatch_once_t onceToken;
        dispatch_once(&onceToken,^{
            _shareInstance = [super allocWithZone:zone];
        });
    }
    return _shareInstance;
}

- (id)copyWithZone:(NSZone *)zone {
    return _shareInstance;
}

- (id)mutableCopyWithZone:(NSZone *)zone {
    return _shareInstance;
}

// key:mdt(url) value:FDownLoader
-(NSMutableDictionary *)downLoaderInfo {
    if (!_downLoaderInfo) {
        _downLoaderInfo = [NSMutableDictionary dictionary];
    }
    return _downLoaderInfo;
}

- (void)downLoader:(NSURL*)url
      downLoadInfo:(DownLoadInfoBlock)downLoadInfo
          progress:(ProgressBlock)progressBlock
           success:(SuccessBlock)successBlock
            failed:(FailedBlock)failedBlock {

    // 1. url
    NSString *urlMD5 = [url.absoluteString md5];
    
    // 2.根据urlMD5,查找相应的下载器
    FDownLoader *downLoader =self.downLoaderInfo[urlMD5];
    
    if (downLoader == nil) {
        downLoader = [[FDownLoader alloc] init];
        self.downLoaderInfo[urlMD5] = downLoader;
    }

    [downLoader downLoader:url
              downLoadInfo:downLoadInfo
                  progress:progressBlock
                   success:^(NSString *filePath) {
        // 拦截 block
        [self.downLoaderInfo removeObjectForKey:urlMD5];
        successBlock(filePath);
    } failed:failedBlock];
}

- (void)pauseWithURL:(NSURL *)url {
    NSString *urlMD5 = [url.absoluteString md5];
    FDownLoader *downLoader =self.downLoaderInfo[urlMD5];
    [downLoader pauseCurrentTask];
}

- (void)resumeWithURL:(NSURL *)url {
    NSString *urlMD5 = [url.absoluteString md5];
    FDownLoader *downLoader =self.downLoaderInfo[urlMD5];
    [downLoader resumeCurrentTask];
}

- (void)cancleWithURL:(NSURL *)url {
    NSString *urlMD5 = [url.absoluteString md5];
    FDownLoader *downLoader =self.downLoaderInfo[urlMD5];
    [downLoader cancleCurrentTask];
}

// 暂停所有
- (void)pauseAll {
    [self.downLoaderInfo.allValues performSelector:@selector(pauseCurrentTask) withObject:nil];
}

// 恢复所有
- (void)resumeAll {
    [self.downLoaderInfo.allValues performSelector:@selector(resumeCurrentTask) withObject:nil];
}

@end
