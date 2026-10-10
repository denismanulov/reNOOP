//  FriendsAvatars.swift
//  NOOP · Friends — pictures.
//
//  A picture is fetched with a signed request (the server shows one only to its owner and friends) and
//  kept in the caches directory under the account id and the picture's revision. The revision changes
//  whenever the owner changes the picture, so a kept file is never stale and a picture is fetched once
//  per revision. Turning Friends off deletes the lot.
//
//  The wearer's own picture is the profile photo the app already has: the tab has no picker of its own.

import Foundation

enum FriendsAvatars {
    /// The longest side a fetched picture is kept at: no avatar is drawn larger, so a large upload
    /// cannot cost memory.
    private static let keptSide = 512

    private static let folder: URL? = FileManager.default
        .urls(for: .cachesDirectory, in: .userDomainMask).first?
        .appendingPathComponent("friends", isDirectory: true)
        .appendingPathComponent("avatars", isDirectory: true)

    /// Pictures already read this session, by "id:rev". A few dozen small JPEGs at most.
    private static let memory: NSCache<NSString, NSData> = {
        let cache = NSCache<NSString, NSData>()
        cache.countLimit = 64
        return cache
    }()

    /// The picture already read this session, without touching the disk.
    static func cached(id: String, rev: Int) -> Data? {
        rev > 0 ? memory.object(forKey: "\(id):\(rev)" as NSString) as Data? : nil
    }

    /// The picture of `id` at revision `rev`: from memory, else the disk, else the server. Nil when the
    /// account has none (`rev` is 0), Friends is off, offline, or the bytes are not an image. The id
    /// becomes a file name, so one that is not an id is refused.
    @MainActor
    static func load(id: String, rev: Int, store: FriendsStore? = nil) async -> Data? {
        guard rev > 0, FriendsID.isValid(id) else { return nil }
        if let held = cached(id: id, rev: rev) { return held }
        let file = folder?.appendingPathComponent("\(id)_\(rev)")
        if let file, let kept = try? Data(contentsOf: file) {
            memory.setObject(kept as NSData, forKey: "\(id):\(rev)" as NSString)
            return kept
        }
        guard let fetched = await (store ?? .shared).avatarBytes(of: id),
              let picture = AvatarImage.downscaledJPEG(from: fetched, maxDimension: CGFloat(keptSide)) else { return nil }
        if let folder, let file {
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            // One picture per person: an older revision is of no further use.
            for old in (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
            where old.hasPrefix("\(id)_") {
                try? FileManager.default.removeItem(at: folder.appendingPathComponent(old))
            }
            try? picture.write(to: file, options: [.atomic])
        }
        memory.setObject(picture as NSData, forKey: "\(id):\(rev)" as NSString)
        return picture
    }

    /// Deletes every kept picture (Friends turned off, account deleted).
    static func clear() {
        memory.removeAllObjects()
        if let folder { try? FileManager.default.removeItem(at: folder) }
    }

    /// `photo` made to fit the server's picture limit, as a JPEG. The profile photo is already a small
    /// JPEG (256 px), so this normally returns it untouched; anything else is re-encoded at falling size
    /// and quality until it fits. Nil when the bytes are not an image or cannot be made small enough.
    static func fitForUpload(_ photo: Data, maxBytes: Int = FriendsLimits.maxAvatarBytes) -> Data? {
        if photo.count <= maxBytes, photo.starts(with: [0xFF, 0xD8, 0xFF]) { return photo }
        for side in [512, 384, 256, 192, 128] {
            for quality in [0.85, 0.7, 0.55] {
                if let jpeg = AvatarImage.downscaledJPEG(from: photo, maxDimension: CGFloat(side), quality: quality),
                   jpeg.count <= maxBytes {
                    return jpeg
                }
            }
        }
        return nil
    }
}
