//
//  MiniCPM-V-demo-Bridging-Header.h
//  MiniCPM-V-demo
//
//  Created by Alex on 2024/6/7.
//

#ifndef MiniCPM_V_demo_Bridging_Header_h
#define MiniCPM_V_demo_Bridging_Header_h

/// 文件下载器
#import "FDownLoader.h"
#import "FFileTool.h"
#import "NSString+MD5.h"
#import "FDownLoaderManager.h"

/// MTMD 多模态推理
///
/// MBMtmd 是 demo 仓内自带的薄 C 桥接层（MTMDWrapper/Bridge/MBMtmd.{h,mm}），
/// 调用上游 llama.cpp master 的公共 mtmd / mtmd-helper / llama 接口。
/// 旧的 `<llama/mtmd-ios.h>` 来自我们之前 fork 的 llama.cpp 私有分支，
/// 与 master 同步后已不再可用。
#import "MBMtmd.h"

#endif /* MiniCPM_V_demo_Bridging_Header_h */
