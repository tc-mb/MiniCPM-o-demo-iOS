//
//  FDownLoaderManager.h
//  FDownLoadDemo
//
//  Created by allison on 2018/8/25.
//  Copyright © 2018年 allison. All rights reserved.
//

#import <Foundation/Foundation.h>
#import "FDownLoader.h"

@interface FDownLoaderManager : NSObject

// 单例
// 1.无论通过怎样的方式，创建出来，只有一个实例 (alloc copy mutableCopy)
// 2.通过某种方式，可以获取同一个对象，但是也可以通过其他方式创建出来新的对象
// 2.1 系统提供的 [NSUserDefaults standardUserDefaults];
// 2.2 自己创建的 [[NSUserDefaults alloc]init];
+ (instancetype)shareInstance;

/// 获取下载信息
@property (nonatomic, readonly) NSMutableDictionary *downLoaderInfo;

- (void)downLoader:(NSURL*)url
      downLoadInfo:(DownLoadInfoBlock)downLoadInfo
          progress:(ProgressBlock)progressBlock
           success:(SuccessBlock)successBlock
            failed:(FailedBlock)failedBlock;

/// 暂停
- (void)pauseWithURL:(NSURL *)url;

/// 恢复
- (void)resumeWithURL:(NSURL *)url;

/// 取消
- (void)cancleWithURL:(NSURL *)url;

/// 暂停所有
- (void)pauseAll;

/// 恢复所有
- (void)resumeAll;

@end
