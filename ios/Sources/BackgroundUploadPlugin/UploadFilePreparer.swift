import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

struct PreparedFile {
    let path: String
    let size: Int64
    let mimeType: String?
}

/// Prepares a file for upload: downscales/re-encodes images when requested. All output
/// lives under Caches/doinn-bg-upload so `acknowledge` can safely clean it up afterwards.
enum UploadFilePreparer {
    private static let cacheSubdir = "doinn-bg-upload"
    private static let defaultJpegQuality = 0.8
    static let defaultOrphanMaxAgeMs = 86_400_000

    static func cacheDirectory() throws -> URL {
        let base = try FileManager.default.url(
            for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )
        let dir = base.appendingPathComponent(cacheSubdir, isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    static func isInsideCacheDir(_ path: String) -> Bool {
        guard let dir = try? cacheDirectory() else { return false }
        let target = URL(fileURLWithPath: path).standardizedFileURL.path
        let base = dir.standardizedFileURL.path
        return target == base || target.hasPrefix(base + "/")
    }

    /// Deletes files under the cache subdir that are older than `maxAgeMs` and not referenced
    /// as `filePath` by any job in `referencedPaths`. Never touches anything outside the
    /// subdir; a missing subdir is not an error (nothing to sweep).
    static func cleanupOrphans(referencedPaths: Set<String>, maxAgeMs: Int) -> Int {
        guard let base = try? FileManager.default.url(
            for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: false
        ) else { return 0 }
        let dir = base.appendingPathComponent(cacheSubdir, isDirectory: true)
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: dir.path),
              let contents = try? fileManager.contentsOfDirectory(atPath: dir.path) else {
            return 0
        }

        let cutoff = Date(timeIntervalSinceNow: -Double(maxAgeMs) / 1000)
        var deleted = 0
        for name in contents {
            let fileURL = dir.appendingPathComponent(name)
            guard let attrs = try? fileManager.attributesOfItem(atPath: fileURL.path),
                  let modDate = attrs[.modificationDate] as? Date,
                  modDate < cutoff,
                  !referencedPaths.contains(fileURL.path) else { continue }
            if (try? fileManager.removeItem(at: fileURL)) != nil {
                deleted += 1
            }
        }
        return deleted
    }

    static func prepare(
        filePath: String,
        imageMaxDimension: Int?,
        imageQuality: Double?,
        deleteSource: Bool = false
    ) throws -> PreparedFile {
        let fileURL = resolveFileURL(filePath)
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            throw NSError(domain: "BackgroundUpload", code: 10,
                          userInfo: [NSLocalizedDescriptionKey: "File not found: \(fileURL.path)"])
        }

        if let maxDimension = imageMaxDimension, maxDimension > 0,
           let downscaled = downscaleImage(
               fileURL: fileURL,
               maxDimension: maxDimension,
               quality: imageQuality ?? defaultJpegQuality
           ) {
            return downscaled
        }

        if isInsideCacheDir(fileURL.path) {
            let attrs = try FileManager.default.attributesOfItem(atPath: fileURL.path)
            let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
            return PreparedFile(path: fileURL.path, size: size, mimeType: mimeType(for: fileURL))
        }
        return try copyToCache(fileURL: fileURL, deleteSource: deleteSource)
    }

    private static func resolveFileURL(_ filePath: String) -> URL {
        if filePath.hasPrefix("file://"), let url = URL(string: filePath) {
            return url
        }
        return URL(fileURLWithPath: filePath)
    }

    private static func mimeType(for url: URL) -> String? {
        guard let type = UTType(filenameExtension: url.pathExtension) else { return nil }
        return type.preferredMIMEType
    }

    /// Returns nil (never throws) when the source can't be decoded as an image, so callers
    /// can fall back to the original path instead of crashing on a mislabeled "image".
    private static func downscaleImage(fileURL: URL, maxDimension: Int, quality: Double) -> PreparedFile? {
        guard let source = CGImageSourceCreateWithURL(fileURL as CFURL, nil) else { return nil }
        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxDimension,
            kCGImageSourceCreateThumbnailWithTransform: true
        ]
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions as CFDictionary) else {
            return nil
        }
        // JPEG has no alpha channel; thumbnails come back as premultiplied RGBA, which makes
        // ImageIO log "trying to save an opaque image with AlphaPremulLast" on every photo.
        let cgImage = stripAlpha(from: thumbnail) ?? thumbnail

        guard let outputURL = try? uniqueOutputURL(originalPath: fileURL.path, forcedExtension: "jpg"),
              let destination = CGImageDestinationCreateWithURL(
                  outputURL as CFURL, UTType.jpeg.identifier as CFString, 1, nil
              ) else {
            return nil
        }
        let destinationOptions: [CFString: Any] = [
            kCGImageDestinationLossyCompressionQuality: quality
        ]
        CGImageDestinationAddImage(destination, cgImage, destinationOptions as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }

        guard let attrs = try? FileManager.default.attributesOfItem(atPath: outputURL.path) else {
            return nil
        }
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        return PreparedFile(path: outputURL.path, size: size, mimeType: "image/jpeg")
    }

    private static func stripAlpha(from image: CGImage) -> CGImage? {
        let alphaInfo = image.alphaInfo
        if alphaInfo == .none || alphaInfo == .noneSkipLast || alphaInfo == .noneSkipFirst {
            return image
        }
        guard let context = CGContext(
            data: nil,
            width: image.width,
            height: image.height,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: image.colorSpace ?? CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        return context.makeImage()
    }

    private static func uniqueOutputURL(originalPath: String, forcedExtension: String) throws -> URL {
        let dir = try cacheDirectory()
        let originalName = (originalPath as NSString).lastPathComponent
        let baseName = (originalName as NSString).deletingPathExtension
        let suffix = "\(Int(Date().timeIntervalSince1970 * 1000))-\(UUID().uuidString.prefix(8))"
        return dir.appendingPathComponent("\(baseName)-\(suffix).\(forcedExtension)")
    }

    /// Copies (or moves, when `deleteSource` — compressor output we own) a plain file
    /// into the managed cache subdir. Managed files survive OS tmp purges during long
    /// uploads and are deleted by `acknowledge`/orphan sweep. `moveItem` handles
    /// cross-volume moves (copy+remove) itself.
    private static func copyToCache(fileURL: URL, deleteSource: Bool) throws -> PreparedFile {
        let ext = fileURL.pathExtension.isEmpty ? "bin" : fileURL.pathExtension
        let outputURL = try uniqueOutputURL(originalPath: fileURL.path, forcedExtension: ext)
        if deleteSource {
            try FileManager.default.moveItem(at: fileURL, to: outputURL)
        } else {
            try FileManager.default.copyItem(at: fileURL, to: outputURL)
        }
        let attrs = try FileManager.default.attributesOfItem(atPath: outputURL.path)
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        return PreparedFile(path: outputURL.path, size: size, mimeType: mimeType(for: outputURL))
    }
}
