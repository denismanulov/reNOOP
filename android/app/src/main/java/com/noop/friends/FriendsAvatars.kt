package com.noop.friends

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

// MARK: - Friends: pictures
//
// A picture is fetched with a signed request (the server shows one only to its owner and their friends)
// and kept in the app's cache directory under the account id and the picture's revision. The revision
// changes whenever the owner changes the picture, so a cached file is never stale and a picture is
// fetched once per revision. Friends going off deletes the lot.

object FriendsAvatars {
    private const val DIR = "friends_avatars"

    /** Decoded pictures in memory, by "id:rev". A few dozen small bitmaps at most. */
    private val memory = LruCache<String, ImageBitmap>(64)

    private fun dir(context: Context) = File(context.applicationContext.cacheDir, DIR)

    /** The picture already decoded this session, without touching the disk. */
    fun cached(id: String, rev: Int): ImageBitmap? = if (rev <= 0) null else memory.get("$id:$rev")

    /**
     * The picture of the account [id] at revision [rev]: from memory, else the disk cache, else the
     * server. Null when the account has none ([rev] is 0), Friends is off, offline, or the bytes are not
     * an image. An id that has not the shape of one names no file and no request.
     */
    suspend fun load(context: Context, id: String, rev: Int): ImageBitmap? {
        if (rev <= 0 || !FriendsId.isValid(id)) return null
        cached(id, rev)?.let { return it }
        return withContext(Dispatchers.IO) {
            val store = FriendsStore.get(context)
            if (!store.isOn) return@withContext null
            val folder = dir(context)
            val file = File(folder, "${id}_$rev")
            val onDisk = if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
            val bytes = onDisk ?: run {
                val fetched = store.avatarBytes(id) ?: return@withContext null
                runCatching {
                    folder.mkdirs()
                    // One picture per person: an older revision is of no further use.
                    folder.listFiles { f -> f.name.startsWith("${id}_") }?.forEach { it.delete() }
                    file.writeBytes(fetched)
                }
                fetched
            }
            decode(bytes)?.also { memory.put("$id:$rev", it) }
        }
    }

    /** Deletes every cached picture (Friends going off on this phone). */
    fun clear(context: Context) {
        memory.evictAll()
        runCatching { dir(context).deleteRecursively() }
    }

    /** Decodes [bytes] no larger than an avatar is ever drawn, so a large upload cannot cost memory. */
    private fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DECODE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()

    private const val MAX_DECODE_PX = 512

    /**
     * [jpeg] made to fit the server's picture limit. The profile photo is already a small JPEG (256 px),
     * so this normally returns it untouched; a larger one is re-encoded at falling quality and then at
     * falling size until it fits. Null when the bytes are not an image or cannot be made small enough.
     */
    fun fitForUpload(jpeg: ByteArray, maxBytes: Int = FriendsLimits.MAX_AVATAR_BYTES): ByteArray? {
        if (jpeg.size <= maxBytes) return jpeg
        val source = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }.getOrNull() ?: return null
        var bitmap = source
        repeat(MAX_SHRINKS) {
            for (quality in QUALITIES) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= maxBytes) return out.toByteArray()
            }
            val width = (bitmap.width * 3 / 4).coerceAtLeast(1)
            val height = (bitmap.height * 3 / 4).coerceAtLeast(1)
            bitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        return null
    }

    private val QUALITIES = intArrayOf(85, 70, 55)
    private const val MAX_SHRINKS = 8
}
