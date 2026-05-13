//
//  MiniCPMModelConst.swift
//  MiniCPM-V-demo
//
//  Created by Alex on 2024/6/17.
//

import Foundation

/// 定义 MiniCPM 模型常量
struct MiniCPMModelConst {
    
    // MARK: - Q4_K_M 2.6 多模态主模型
    
    /// MiniCPM 多模态-主模型 Q4_K_M 文件名
    static let modelQ4_K_MFileName = "ggml-model-Q4_0.gguf"
    
    /// MiniCPM 多模态-主模型 Q4_K_M oss 下载地址
    static let modelQ4_K_MURLString = "https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf/resolve/main/ggml-model-Q4_0.gguf"
    
    /// 显示在 UI 上名字-Q4_K_M
    static let modelQ4_K_MDisplayedName = "MiniCPM-V 2.6 8B LLM INT4"
    
    /// Q4_K_M gguf 文件 md5 值
    static let modelQ4_K_MMD5 = "2d6497c0ef0957af80a5d6b69e0de89b"
    
    
    // MARK: - 2.6 mmproj VIT 模型
    
    /// MiniCPM 多模态-mmproj 模型 文件名
    static let mmprojFileName = "mmproj-model-f16.gguf"
    
    /// MiniCPM 多模态-mmproj 模型 NAS 下载地址
    static let mmprojURLString = "https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf/resolve/main/mmproj-model-f16.gguf"
    
    /// 显示在 UI 上名字-mmproj
    static let modelMMProjDisplayedName = "MiniCPM-V 2.6 8B VPM"
    
    /// mmproj gguf 文件 md5 值
    static let modelMMProjMD5 = "b539e887cc2b598f560465be65802b1b"
    
    
    // MARK: - 2.6 ANE 利用模块
    
    /// ANE 利用压缩包 文件名
    static let mlmodelcZipFileName = "ane_minicpmv26_f32.mlmodelc.zip"
    
    /// ANE 模型压缩包下载地址
    static let mlmodelcZipFileURLString = "https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf/resolve/main/ane_minicpmv26_f32.mlmodelc.zip"
    
    /// ANE 利用显示在设置页的名称
    static let mlmodelcZipFileDisplayedName = "MiniCPM-V 2.6 8B ANE"
    
    /// ANE 利用压缩包 md5
    static let mlmodelcZipFileMD5 = "ddf77e6d274259dbcb35cd9e5ca26d1a"
    
    
    
    // MARK: - mbv4 多模态语言模型
    
    /// MiniCPM 多模态-主模型 Q4_K_M 文件名
    static let modelv4_Q4_K_M_FileName = "ggml-model-Q4_0.gguf"
    
    /// MiniCPM 多模态-主模型 Q4_K_M oss 下载地址
    static let modelv4_Q4_K_M_URLString = "https://huggingface.co/openbmb/MiniCPM-V-4-gguf/resolve/main/ggml-model-Q4_0.gguf"

    static let modelv4_Q4_K_M_BackUpURLString = "https://modelscope.cn/api/v1/models/OpenBMB/MiniCPM-V-4-gguf/repo?Revision=master&FilePath=ggml-model-Q4_0.gguf"
    
    /// 显示在 UI 上名字-Q4_K_M
    static let modelv4_Q4_K_M_DisplayedName = "MiniCPM-V 4.0 4B LLM INT4"
    
    /// Q4_K_M gguf 文件 md5 值
    static let modelv4_Q4_K_M_MD5 = "8fc4cc88e5ea73472ae795b57a0e7fdd"
    
    
    // MARK: - mbv4 mmproj VIT 模型
    
    /// MiniCPM 多模态-mmproj 模型 文件名
    static let mmprojv4_FileName = "mmproj-model-f16-iOS.gguf"
    
    /// MiniCPM 多模态-mmproj 模型 NAS 下载地址
    static let mmprojv4_URLString = "https://huggingface.co/openbmb/MiniCPM-V-4-gguf/resolve/main/mmproj-model-f16.gguf"

    static let mmprojv4_BackUpURLString = "https://modelscope.cn/api/v1/models/OpenBMB/MiniCPM-V-4-gguf/repo?Revision=master&FilePath=mmproj-model-f16.gguf"
    
    /// 显示在 UI 上名字-mmproj
    static let modelMMProjv4_DisplayedName = "MiniCPM-V 4.0 4B VPM"
    
    /// mmproj gguf 文件 md5 值
    static let modelMMProjv4_MD5 = "fe15375bb4c579858df6054d2a8b639d"
    
    // MARK: - mbv4 ANE 利用模块
    
    /// ANE 利用压缩包 文件名
    static let mlmodelcv4_ZipFileName = "coreml_minicpmv40_vit_f16.mlmodelc.zip"
    
    /// ANE 模型压缩包下载地址
    static let mlmodelcv4_ZipFileURLString = "https://huggingface.co/openbmb/MiniCPM-V-4-gguf/resolve/main/coreml_minicpmv40_vit_f16.mlmodelc.zip"
    
    static let mlmodelcv4_ZipFileBackUpURLString = "https://modelscope.cn/api/v1/models/OpenBMB/MiniCPM-V-4-gguf/repo?Revision=master&FilePath=coreml_minicpmv40_vit_f16.mlmodelc.zip"
    
    /// ANE 利用显示在设置页的名称
    static let mlmodelcv4_ZipFileDisplayedName = "MiniCPM-V 4.0 4B ANE"
    
    /// ANE 利用压缩包 md5
    static let mlmodelcv4_ZipFileMD5 = "150a316e49dee3da04d72039ee2ca390"
    
    
    // MARK: - MiniCPM-V 4.6 多模态语言模型

    /// V4.6 主模型文件名（落盘到 Documents/，命名带 v4.6 前缀以避免与 v2.6 / v4.0 同目录平铺时撞名）。
    /// 注意：这一份 LLM 在 OBS / HF / ModelScope 三方完全同源（同 MD5），切换下载源不会影响老用户已下载的本地文件，
    /// 也不需要 bump 文件名 / MD5。
    static let modelv46_FileName = "MiniCPM-V-4_6-Q4_K_M.gguf"

    /// V4.6 显示名
    static let modelv46_DisplayedName = "MiniCPM-V 4.6 LLM INT4"

    /// V4.6 主模型下载地址（HuggingFace 主源，对齐 v4 的源策略）
    static let modelv46_Q4_K_M_URLString = "https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf/resolve/main/MiniCPM-V-4_6-Q4_K_M.gguf"

    /// V4.6 主模型备用下载地址（ModelScope 国内镜像，HF 不通时由 MBModelDownloadHelperV2 自动 fallback）
    static let modelv46_Q4_K_M_BackUpURLString = "https://modelscope.cn/api/v1/models/OpenBMB/MiniCPM-V-4.6-gguf/repo?Revision=master&FilePath=MiniCPM-V-4_6-Q4_K_M.gguf"

    /// V4.6 主模型 md5（OBS / HF / ModelScope 三源同源，无需 bump）
    static let modelv46_Q4_K_M_MD5 = "fd778481dd56b6036dd8f9cf7c1519cf"


    // MARK: - MiniCPM-V 4.6 mmproj VIT 模型

    /// V4.6 mmproj 文件名（落盘到 Documents/）。
    ///
    /// 这里**有意带 `-master-` 后缀**，与老的 `MiniCPM-V-4_6-mmproj-f16.gguf` 区分开：
    /// - 旧名：OBS 上 demo fork 转出的 `clip.projector_type=merger` 版本
    /// - 新名：HF / ModelScope 上 OpenBMB 官方转出的 `minicpmv4_6` 版本（与 upstream master 兼容）
    ///
    /// 两份文件互不兼容（master 加载老 mmproj 会 fail；demo fork 加载新 mmproj 走 unknown projector
    /// 也会 fail）。换名让两份在 Documents/ 中可以共存且 modelsExist() 不会误用残留旧文件。
    /// 老文件名登记在 `staleMMProjv46_FileNames` 里，启动时由 MBV46ModelDownloadManager
    /// 主动 purge —— 否则那 1.1GB 旧文件会一直占着磁盘。
    static let mmprojv46_FileName = "MiniCPM-V-4_6-mmproj-master-f16.gguf"

    /// V4.6 mmproj 显示名
    static let modelMMProjv46_DisplayedName = "MiniCPM-V 4.6 VPM"

    /// V4.6 mmproj 下载地址（HuggingFace 主源）
    static let mmprojv46_URLString = "https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf/resolve/main/mmproj-model-f16.gguf"

    /// V4.6 mmproj 备用下载地址（ModelScope 国内镜像，HF 不通时由 MBModelDownloadHelperV2 自动 fallback）
    static let mmprojv46_BackUpURLString = "https://modelscope.cn/api/v1/models/OpenBMB/MiniCPM-V-4.6-gguf/repo?Revision=master&FilePath=mmproj-model-f16.gguf"

    /// V4.6 mmproj md5（HF / ModelScope 上的 OpenBMB 官方版本，跟 upstream master 兼容）
    static let modelMMProjv46_MD5 = "54aea6e04d752f47309a48f12795a1a3"

    /// 启动时主动 purge 的老 mmproj 文件名集合。
    ///
    /// 用途：处理"老用户从 OBS 下载过旧 demo-fork mmproj"的迁移场景。这份旧 mmproj 在 master
    /// 适配版本里已经不能用，但 modelsExist() fast-path 只看文件存在不校 MD5，会把残留文件直接
    /// 喂给 native 加载导致闪退（参照 AGENTS.md《客户端 ↔ OBS 模型一致性》一节）。
    static let staleMMProjv46_FileNames: [String] = [
        "MiniCPM-V-4_6-mmproj-f16.gguf",  // OBS demo-fork merger 版本，master 加载 fail
    ]
    
    
    // MARK: - MiniCPM-V 4.6 ANE 模块
    
    /// V4.6 ANE CoreML 目录名（编译后的 .mlmodelc，或 Xcode 可直接加载的 .mlpackage 包）
    static let mlmodelcv46_DirName = "coreml_minicpmv46_vit_all_f32.mlmodelc"
    
    /// 在 Documents 中依次尝试的文件名（任一路径存在即作为 CoreML 路径）
    static let mlmodelcv46_CandidateFileNames: [String] = [
        "coreml_minicpmv46_vit_all_f32.mlmodelc",
        "coreml_minicpmv46_vit_all_f32.mlpackage",
        "coreml_minicpmv46_vit_all_f16.mlpackage",
    ]
    
    /// V4.6 ANE 显示名
    static let mlmodelcv46_DisplayedName = "MiniCPM-V 4.6 ANE"

    /// V4.6 ANE zip 文件名（下载到 Documents 后解压得到 .mlmodelc 目录）
    static let mlmodelcv46_ZipFileName = "coreml_minicpmv46_vit_all_f32.mlmodelc.zip"

    /// V4.6 ANE zip 下载地址
    static let mlmodelcv46_ZipFileURLString = "https://data-transfer-huawei.obs.cn-north-4.myhuaweicloud.com/minicpmv46-instruct/coreml_minicpmv46_vit_all_f32.mlmodelc.zip"

    /// V4.6 ANE zip md5（demo 分支重转：基于 ckpt/MiniCPM-V-4_6 封板权重 + HF 命名映射）
    static let mlmodelcv46_ZipFileMD5 = "4ea0fbdb9b975e411b0faf478beb1d84"
    
}
