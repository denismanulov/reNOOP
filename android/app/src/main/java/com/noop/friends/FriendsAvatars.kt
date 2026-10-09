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
// A picture is fetched with the session token (the server shows one only to its owner, friends and across
// a pending request) and kept in the app's cache directory under the nickname and the picture's revision.
// The revision changes whenever the owner changes the picture, so a cached file is never stale and a
// picture is fetched once per revision. Signing out deletes the lot.

object FriendsAvatars {
    private const val DIR = "friends_avatars"

    /** Decoded pictures in memory, by "nick:rev". A few dozen small bitmaps at most. */
    private val memory = LruCache<String, ImageBitmap>(64)

    private fun dir(context: Context) = File(context.applicationContext.cacheDir, DIR)

    /** The picture already decoded this session, without touching the disk. */
    fun cached(nick: String, rev: Int): ImageBitmap? = if (rev <= 0) null else memory.get("$nick:$rev")

    /**
     * The picture of [nick] at revision [rev]: from memory, else the disk cache, else the server. Null
     * when the account has none ([rev] is 0), signed out, offline, or the bytes are not an image.
     */
    suspend fun load(context: Context, nick: String, rev: Int): ImageBitmap? {
        if (rev <= 0 || FriendsNick.clean(nick) != nick) return null
        cached(nick, rev)?.let { return it }
        return withContext(Dispatchers.IO) {
            val store = FriendsStore.get(context)
            if (!store.isSignedIn) return@withContext null
            val folder = dir(context)
            val file = File(folder, "${nick}_$rev")
            val onDisk = if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
            val bytes = onDisk ?: run {
                val api = FriendsApi.create(store.serverUrl, store.token()) ?: return@withContext null
                val fetched = api.avatar(nick).valueOrNull ?: return@withContext null
                runCatching {
                    folder.mkdirs()
                    // One picture per person: an older revision is of no further use.
                    folder.listFiles { f -> f.name.startsWith("${nick}_") }?.forEach { it.delete() }
                    file.writeBytes(fetched)
                }
                fetched
            }
            decode(bytes)?.also { memory.put("$nick:$rev", it) }
        }
    }

    /** Deletes every cached picture (sign-out, account deletion). */
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
