//
//  MiniCPMModelConst.swift
//  MiniCPM-demo
//
//  Created by Alex on 2024/6/17.
//

import Foundation

/// 定义 MiniCPM 模型常量
struct MiniCPMModelConst {
    
    // MARK: - Q4_K_M 2.6 多模态主模型
    
    /// MiniCPM 多模态-主模型 Q4_K_M 文件名
    static let modelQ4_K_MFileName = "ggml-model-Q4_K_M_v263_0918.gguf"
    
    /// MiniCPM 多模态-主模型 Q4_K_M oss 下载地址
    static let modelQ4_K_MURLString = "http://192.168.11.125/ggml-model-Q4_K_M_v263_0918.gguf"
    
    /// 显示在 UI 上名字-Q4_K_M
    static let modelQ4_K_MDisplayedName = "MiniCPM-V 2.6 8B LLM INT4"
    
    /// Q4_K_M gguf 文件 md5 值
    static let modelQ4_K_MMD5 = "2d6497c0ef0957af80a5d6b69e0de89b"
    
    
    // MARK: - 2.6 mmproj VIT 模型
    
    /// MiniCPM 多模态-mmproj 模型 文件名
    static let mmprojFileName = "mmproj-model-f16_v263_0918.gguf"
    
    /// MiniCPM 多模态-mmproj 模型 NAS 下载地址
    static let mmprojURLString = "http://192.168.11.125/mmproj-model-f16_v263_0918.gguf"
    
    /// 显示在 UI 上名字-mmproj
    static let modelMMProjDisplayedName = "MiniCPM-V 2.6 8B VPM"
    
    /// mmproj gguf 文件 md5 值
    static let modelMMProjMD5 = "b539e887cc2b598f560465be65802b1b"
    
    
    // MARK: - 2.6 ANE 利用模块
    
    /// ANE 利用压缩包 文件名
    static let mlmodelcZipFileName = "ane_MiniCPM-V-2_6_3_f32_b1.mlmodelc.zip"
    
    /// ANE 模型压缩包下载地址
    static let mlmodelcZipFileURLString = "http://192.168.11.125/ane_MiniCPM-V-2_6_3_f32_b1.mlmodelc.zip"
    
    /// ANE 利用显示在设置页的名称
    static let mlmodelcZipFileDisplayedName = "MiniCPM-V 2.6 8B ANE"
    
    /// ANE 利用压缩包 md5
    static let mlmodelcZipFileMD5 = "ddf77e6d274259dbcb35cd9e5ca26d1a"
    
    
    
    // MARK: - mb4v3b 多模态语言模型
    
    /// MiniCPM 多模态-主模型 Q4_K_M 文件名
    static let model4v3b_Q4_K_M_FileName = "ggml-model-4v3b-Q4_0-0712.gguf"
    
    /// MiniCPM 多模态-主模型 Q4_K_M oss 下载地址
    static let model4v3b_Q4_K_M_URLString = "https://minicpm.oss-cn-beijing.aliyuncs.com/minicpm4v3b/ggml-model-4v3b-Q4_0-0712.gguf"
    
    /// 显示在 UI 上名字-Q4_K_M
    static let model4v3b_Q4_K_M_DisplayedName = "MiniCPM-V 4.0 4B LLM INT4"
    
    /// Q4_K_M gguf 文件 md5 值
    static let model4v3b_Q4_K_M_MD5 = "8fc4cc88e5ea73472ae795b57a0e7fdd"
    
    
    // MARK: - mb4v3b mmproj VIT 模型
    
    /// MiniCPM 多模态-mmproj 模型 文件名
    static let mmproj4v3b_FileName = "mmproj-model-4v3b-f16-0712.gguf"
    
    /// MiniCPM 多模态-mmproj 模型 NAS 下载地址
    static let mmproj4v3b_URLString = "https://minicpm.oss-cn-beijing.aliyuncs.com/minicpm4v3b/mmproj-model-4v3b-f16-0712.gguf"
    
    /// 显示在 UI 上名字-mmproj
    static let modelMMProj4v3b_DisplayedName = "MiniCPM-V 4.0 4B VPM"
    
    /// mmproj gguf 文件 md5 值
    static let modelMMProj4v3b_MD5 = "fe15375bb4c579858df6054d2a8b639d"
    
    // MARK: - mb4v3b ANE 利用模块
    
    /// ANE 利用压缩包 文件名
    static let mlmodelc4v3b_ZipFileName = "ane_minicpm4v3b_vision_f16_b1.mlmodelc.zip"
    
    /// ANE 模型压缩包下载地址
    static let mlmodelc4v3b_ZipFileURLString = "https://minicpm.oss-cn-beijing.aliyuncs.com/minicpm4v3b/ane_minicpm4v3b_vision_f16_b1.mlmodelc.zip"
    
    /// ANE 利用显示在设置页的名称
    static let mlmodelc4v3b_ZipFileDisplayedName = "MiniCPM-V 4.0 4B ANE"
    
    /// ANE 利用压缩包 md5 oss
    // local static let mlmodelc4v3b_ZipFileMD5 = "74f07d49c04b83e7c7e362b24116b205"
    static let mlmodelc4v3b_ZipFileMD5 = "ef04439501cf084e2bb8b27fa60e601e"
    
}
