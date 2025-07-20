//
//  FDownLoader.m
//  FDownLoadDemo
//
//  Created by allison on 2018/8/18.
//  Copyright © 2018年 allison. All rights reserved.
//

#import "FDownLoader.h"
#import "FFileTool.h"

#define kCachePath NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject
#define kTmpPath NSTemporaryDirectory()

@interface FDownLoader () <NSURLSessionDataDelegate>
{
    long long _tmpSize;
    long long _totalSize;
}
@property(nonatomic,strong)NSURLSession *session;
@property(nonatomic,copy)NSString *downLoaderPath;
@property(nonatomic,copy)NSString *downLoadingrPath;
@property(nonatomic,strong)NSOutputStream *outputStream;
@property(nonatomic,weak)NSURLSessionDataTask *dataTask;
@end

@implementation FDownLoader

- (void)downLoader:(NSURL*)url
      downLoadInfo:(DownLoadInfoBlock)downLoadInfo
          progress:(ProgressBlock)progressBlock
           success:(SuccessBlock)successBlock
            failed:(FailedBlock)failedBlock {
    // 1.给所有的block赋值
    self.downLoadInfo = downLoadInfo;
    self.progressChange = progressBlock;
    self.successBlock = successBlock;
    self.failedBlock = failedBlock;
    
    // 2.开始下载
    [self downLoader:url];
}

-(NSURLSession *)session {
    if (!_session) {
        NSURLSessionConfiguration *config = [NSURLSessionConfiguration defaultSessionConfiguration];
        _session = [NSURLSession sessionWithConfiguration:config delegate:self delegateQueue:[NSOperationQueue mainQueue]];
    }
    return _session;
}

- (void)setProgress:(float)progress {
    _progress = progress;
    if (self.progress) {
        self.progressChange(_progress);
    }
}
#pragma mark -- <数据事件传递>
- (void)setState:(FDownLoadState)state {
    // 数据拦截
    if (_state == state) {
        return;
    }
    _state = state;
    // 代理 block 通知
    if (self.stateChange) {
        self.stateChange(_state);
    }
    if (_state == FDownLoadStatePauseSuccess && self.successBlock) {
        self.successBlock(self.downLoaderPath);
    }
    if (_state == FDownLoadStatePauseFailed && self.failedBlock) {
        self.failedBlock();
    }
}

- (void)downLoader:(NSURL*)url {
    /// 内部实现
    // 1.真正的从头开始下载
    /// 2.如果任务存在，继续下载
    // 当前任务肯定存在
    if ([url isEqual:self.dataTask.originalRequest.URL]) {
        // 判断当前的状态，如果是暂停状态
        if (self.state == FDownLoadStatePause) {
            // 继续
            [self resumeCurrentTask];
            return;
        }
    }
    [self resumeCurrentTask];
    
    //1.文件的存放
    NSString *fileName = url.lastPathComponent;
    /// 下载完成的路径
    self.downLoaderPath = [kCachePath stringByAppendingPathComponent:fileName];
    /// 临时文件路径
    self.downLoadingrPath = [kTmpPath stringByAppendingPathComponent:fileName];
    //2.判断，URL地址，对应的资源，是否已经下载完毕
    //2.1 告诉外界，下载完毕，并且传递相关信息(本地的的路径，文件的大小) return
    if ([FFileTool fileExists:self.downLoaderPath]) {
        // 告诉外界，已经下载完成;
        self.state = FDownLoadStatePauseSuccess;
        return;
    }
    //2.2 检测，临时文件是否存在
    // 2.2.1 临时文件不存在
    if (![FFileTool fileExists:self.downLoadingrPath]) {
        // 从0字节开始请求资源
        [self downLoadWithURL:url offset:0];
        return;
    }
    // 2.2.2 临时文件存在
    // 获取本地大小
    _tmpSize = [FFileTool fileSize:self.downLoadingrPath];
    // 获取文件的总大小(需要从网络上获得资源的大小)
    [self downLoadWithURL:url offset:_tmpSize];
}

// 暂停
- (void)pauseCurrentTask {
    if (self.state == FDownLoadStateDownLoading) {
        self.state = FDownLoadStatePause;
        [self.dataTask suspend];
    }
}

// 继续任务
- (void)resumeCurrentTask {
    if (self.dataTask && (self.state == FDownLoadStatePause || self.state == FDownLoadStatePauseFailed || self.state == FDownLoadStateDownLoading)) {
        [self.dataTask resume];
        self.state = FDownLoadStateDownLoading;
    }
}

// 取消
- (void)cancleCurrentTask {
    self.state = FDownLoadStatePause;
    //[self.dataTask cancel];
    [self.session invalidateAndCancel];
    self.session = nil;
}

- (void)cancleAndClean {
    [self cancleAndClean];
    [FFileTool removeFile:self.downLoadingrPath];
    // 下载完成的文件 -> 手动删除某个文件 ->统一清理缓存
}

#pragma mark -- <NSURLSessionDataDelegate>
/// 第一次接收到响应的时候调用(响应投,并没有具体的资源内容)
/*通过这个方法,系统提供的回调代码块,可以控制,是继续请求,还是取消本次请求*/
- (void)URLSession:(NSURLSession *)session
          dataTask:(NSURLSessionDataTask *)dataTask 
didReceiveResponse:(NSHTTPURLResponse *)response
 completionHandler:(void (^)(NSURLSessionResponseDisposition))completionHandler {

    // 总大小 250523
    // NSLog(@"%@",response);
    // 本地缓存
    _totalSize = [response.allHeaderFields[@"Content-Length"] longLongValue];
    
    NSString *contentRangeStr = response.allHeaderFields[@"content-range"];
    
    if (contentRangeStr.length != 0) {
        _totalSize = [[contentRangeStr componentsSeparatedByString:@"/"].lastObject longLongValue];
    }
    
    // 传递给外界:总大小 & 本地存储的文件路径
    if (self.downLoadInfo != nil) {
        self.downLoadInfo(_totalSize);
    }
    
    // 比对本地大小和总大小
    // 2.2.2.1 本地大小 == 总大小 ==>> 移动到下载完成的路径中.
    if (_tmpSize == _totalSize) {
        // 1.移动到下载完成文件夹
        [FFileTool moveFile:self.downLoadingrPath toPath:self.downLoaderPath];
        // 2.取消本次请求
        completionHandler(NSURLSessionResponseCancel);
        // 3.修改状态
        self.state = FDownLoadStatePauseSuccess;
        
        return;
    }
    
    // 2.2.2.2 本地大小 > 总大小  ==>> 删除本地临时缓存(因为此时缓存中是错误的)，从0字节开始下载.
    if (_tmpSize > _totalSize) {
        // 1.删除临时缓存
        [FFileTool removeFile:self.downLoadingrPath];
        
        // 2.取消本次请求
        completionHandler(NSURLSessionResponseCancel);
        
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            // 3.从0开始下载
            [self downLoader:response.URL];
        });
        
        // [self downLoadWithURL:url offset:0]; 如果删除失败,会出现继续往错误的缓存中追加数据的操作.
        
        return;
    }
    
    // 2.2.2.3 本地大小 < 总大小  ==>>  从本地大小开始下载.
    self.state = FDownLoadStateDownLoading;
    self.outputStream = [NSOutputStream outputStreamToFileAtPath:self.downLoadingrPath append:YES];
    [self.outputStream open];
    
    completionHandler(NSURLSessionResponseAllow);
    
}
/// 当用户确定,继续接受数据的时候调用
- (void)URLSession:(NSURLSession *)session
          dataTask:(NSURLSessionDataTask *)dataTask
    didReceiveData:(NSData *)data {
    // 当前以及下载的大小
    _tmpSize += data.length;
    self.progress = 1.0 * _tmpSize / _totalSize;
    
    [self.outputStream write:data.bytes maxLength:data.length];
}

/// 请求完成的时候调用（!=请求成功/失败）
- (void)URLSession:(NSURLSession *)session
              task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    if (error == nil) {
        // 不一定成功 数据是肯定是请求完毕
        // 判断,本地缓存 == 文件总大小(filename: filesize: md5:xxx)
        // 如果等于 ==> 验证,是否文件完整(file md5)
        [FFileTool moveFile:self.downLoadingrPath toPath:self.downLoaderPath];
        self.state = FDownLoadStatePauseSuccess;
    } else {
        NSLog(@"-->> 有问题: error = %@", error.localizedDescription);
        if (error.code == -999) {
            // 取消
            self.state = FDownLoadStatePause;
            
            // TODO: @sgl 这是我加的，不要删
            [self cancleCurrentTask];
            // [self cancleAndClean];
        } else {
            // 断网
            self.state = FDownLoadStatePauseFailed;
        }
    }
    [self.outputStream close];
}

#pragma mark -- <私有方法>
/**
 根据开始字节去请求资源
 
 @param url url
 @param offset 开始字节
 */
- (void)downLoadWithURL:(NSURL *)url offset:(long long)offset {
    
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url cachePolicy:NSURLRequestReloadIgnoringLocalCacheData timeoutInterval:0];
    [request setValue:[NSString stringWithFormat:@"bytes=%lld-",offset] forHTTPHeaderField:@"Range"];

    // sgl Modified with local npu zip
    // for example: http://192.168.11.125:5081/dav/modelbest/ios_models/v020602/ane_MiniCPM-V-2_6_2_f32_b1.mlmodelc.zip
    if ([url.absoluteString hasPrefix:@"http://192.168.11.125:5081/dav/modelbest/ios_models"]) {
        NSString *tmp = @"modelbest";
        tmp = [NSString stringWithFormat:@"%@:%@", tmp, tmp];
        NSData *tmp2 = [tmp dataUsingEncoding:NSUTF8StringEncoding];
        tmp = [tmp2 base64EncodedStringWithOptions:0];
        tmp = [NSString stringWithFormat:@"Basic %@", tmp];
        [request setValue:tmp forHTTPHeaderField:@"Authorization"];
    }

    // session 分配的task,默认挂起状态
    self.dataTask = [self.session dataTaskWithRequest:request];
    [self resumeCurrentTask];
}

@end
