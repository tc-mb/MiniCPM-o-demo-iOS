import Foundation

enum MBModelArtifactStore {
    private static let receiptPrefix = "model_artifact_validation."

    static func migrateLegacySharedModel(in directory: URL) {
        let fileManager = FileManager.default
        let legacyURL = directory.appendingPathComponent(MiniCPMModelConst.legacySharedModelFileName)
        guard fileManager.fileExists(atPath: legacyURL.path) else { return }

        guard let checksum = MBUtils.md5(for: legacyURL) else {
            remove(legacyURL)
            return
        }

        let destination: URL
        let expectedMD5: String
        switch checksum {
        case MiniCPMModelConst.modelQ4_K_MMD5:
            destination = directory.appendingPathComponent(MiniCPMModelConst.modelQ4_K_MFileName)
            expectedMD5 = MiniCPMModelConst.modelQ4_K_MMD5
        case MiniCPMModelConst.modelv4_Q4_K_M_MD5:
            destination = directory.appendingPathComponent(MiniCPMModelConst.modelv4_Q4_K_M_FileName)
            expectedMD5 = MiniCPMModelConst.modelv4_Q4_K_M_MD5
        default:
            remove(legacyURL)
            return
        }

        if fileManager.fileExists(atPath: destination.path) {
            if validate(destination, expectedMD5: expectedMD5) {
                remove(legacyURL)
                return
            }
            remove(destination)
        }

        do {
            try fileManager.moveItem(at: legacyURL, to: destination)
            markValidated(destination, expectedMD5: expectedMD5)
        } catch {
            debugLog("-->> 迁移旧模型文件失败: \(error.localizedDescription)")
        }
    }

    static func purgeStaleV46MMProj(in directory: URL) {
        for filename in MiniCPMModelConst.staleMMProjv46_FileNames {
            remove(directory.appendingPathComponent(filename))
        }
    }

    static func validatePair(
        modelURL: URL,
        modelMD5: String,
        mmprojURL: URL,
        mmprojMD5: String
    ) -> Bool {
        let modelValid = validate(modelURL, expectedMD5: modelMD5)
        if !modelValid {
            remove(modelURL)
            return false
        }

        let mmprojValid = validate(mmprojURL, expectedMD5: mmprojMD5)
        if !mmprojValid {
            remove(mmprojURL)
        }
        return mmprojValid
    }

    static func validate(_ fileURL: URL, expectedMD5: String) -> Bool {
        guard let fingerprint = fingerprint(for: fileURL, expectedMD5: expectedMD5) else {
            invalidate(fileURL)
            return false
        }

        let defaults = UserDefaults.standard
        if defaults.string(forKey: receiptKey(for: fileURL)) == fingerprint {
            return true
        }

        guard MBUtils.md5(for: fileURL)?.lowercased() == expectedMD5.lowercased() else {
            invalidate(fileURL)
            return false
        }

        defaults.set(fingerprint, forKey: receiptKey(for: fileURL))
        return true
    }

    static func markValidated(_ fileURL: URL, expectedMD5: String) {
        guard let fingerprint = fingerprint(for: fileURL, expectedMD5: expectedMD5) else {
            invalidate(fileURL)
            return
        }
        UserDefaults.standard.set(fingerprint, forKey: receiptKey(for: fileURL))
    }

    static func invalidate(_ fileURL: URL) {
        UserDefaults.standard.removeObject(forKey: receiptKey(for: fileURL))
    }

    private static func remove(_ fileURL: URL) {
        invalidate(fileURL)
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return }
        do {
            try FileManager.default.removeItem(at: fileURL)
        } catch {
            debugLog("-->> 删除无效模型文件失败: \(error.localizedDescription)")
        }
    }

    private static func fingerprint(for fileURL: URL, expectedMD5: String) -> String? {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
              let size = attributes[.size] as? NSNumber,
              let modifiedAt = attributes[.modificationDate] as? Date else {
            return nil
        }
        return "\(expectedMD5.lowercased()):\(size.int64Value):\(modifiedAt.timeIntervalSince1970.bitPattern)"
    }

    private static func receiptKey(for fileURL: URL) -> String {
        receiptPrefix + fileURL.lastPathComponent
    }
}
