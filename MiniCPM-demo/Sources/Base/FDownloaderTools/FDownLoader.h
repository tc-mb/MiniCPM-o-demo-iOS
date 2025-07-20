//
//  FDownLoader.h
//  FDownLoadDemo
//
//  Created by allison on 2018/8/18.
//  Copyright © 2018年 allison. All rights reserved.
//

#import <Foundation/Foundation.h>

typedef NS_ENUM(NSUInteger, FDownLoadState) {
    FDownLoadStatePause, //暂停
    FDownLoadStateDownLoading, //正在下载
    FDownLoadStatePauseSuccess,
    FDownLoadStatePauseFailed
};

typedef void (^DownLoadInfoBlock)(long long totalSize);
typedef void (^StateChangeBlcok) (FDownLoadState state);
typedef void (^ProgressBlock)(float progress);
typedef void (^SuccessBlock)(NSString *filePath);
typedef void (^FailedBlock)(void);

// 一个下载器,对应一个下载任务
@interface FDownLoader : NSObject

- (void)downLoader:(NSURL*)url
      downLoadInfo:(DownLoadInfoBlock)downLoadInfo
          progress:(ProgressBlock)progressBlock
           success:(SuccessBlock)successBlock
            failed:(FailedBlock)failedBlock;

- (void)downLoader:(NSURL*)url;
/// 暂停
- (void)pauseCurrentTask;
/// 取消
- (void)cancleCurrentTask;
/// 取消和删除
- (void)cancleAndClean;
// 继续任务
- (void)resumeCurrentTask;

#pragma mark -- <数据>
@property (nonatomic,assign,readonly)FDownLoadState state;
@property (nonatomic,copy) DownLoadInfoBlock downLoadInfo;
@property (nonatomic,copy) StateChangeBlcok stateChange;
@property (nonatomic,assign,readonly)float progress;
@property (nonatomic,copy) ProgressBlock progressChange;
@property (nonatomic,copy) SuccessBlock successBlock;
@property (nonatomic,copy) FailedBlock failedBlock;

@end
