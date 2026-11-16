package com.doinn.plugins.backgroundupload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

data class PreparedFile(val path: String, val size: Long, val mimeType: String?)

/**
 * Prepares a file for upload: downscales/re-encodes images when requested and copies
 * Android content:// uris to app cache so they survive process death (WorkManager retries
 * run with a plain file path, not a transient content:// read grant).
 */
object UploadFilePreparer {
    private const val TAG = "UploadFilePreparer"
    private const val CACHE_SUBDIR = "doinn-bg-upload"
    private const val DEFAULT_JPEG_QUALITY = 0.8
    const val DEFAULT_ORPHAN_MAX_AGE_MS = 86_400_000L

    fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isInsideCacheDir(context: Context, path: String): Boolean =
        isInside(cacheDir(context), path)

    /** True when [path] resolves, symlinks and `..` included, to [dir] itself or something below it. */
    fun isInside(dir: File, path: String): Boolean {
        return try {
            val target = File(path).canonicalFile
            val base = dir.canonicalFile
            target == base || target.path.startsWith(base.path + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    /** Deletes every regular file directly in [dir] and returns how many were removed; 0 when [dir] is missing. */
    fun deleteAllIn(dir: File): Int {
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) deleted++
        }
        return deleted
    }

    /**
     * Deletes files under the cache subdir that are older than [maxAgeMs] and not referenced
     * as `filePath` by any job in [referencedPaths]. Never touches anything outside the
     * subdir; a missing subdir is not an error (nothing to sweep).
     */
    fun cleanupOrphans(context: Context, referencedPaths: Set<String>, maxAgeMs: Long): Int {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        if (!dir.exists()) return 0
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff && file.absolutePath !in referencedPaths) {
                if (file.delete()) deleted++
            }
        }
        return deleted
    }

    fun prepare(
        context: Context,
        filePath: String,
        imageMaxDimension: Int?,
        imageQuality: Double?,
        deleteSource: Boolean = false
    ): PreparedFile {
        val isContentUri = filePath.startsWith("content://")
        val sourceMimeType = resolveMimeType(context, filePath, isContentUri)

        if (imageMaxDimension != null && imageMaxDimension > 0) {
            val downscaled = tryDownscaleImage(
                context,
                filePath,
                isContentUri,
                sourceMimeType,
                imageMaxDimension,
                imageQuality ?: DEFAULT_JPEG_QUALITY
            )
            if (downscaled != null) return downscaled
        }

        if (isContentUri) {
            return copyContentUriToCache(context, filePath, sourceMimeType)
        }

        val original = File(Uri.parse(filePath).path ?: filePath)
        if (!original.exists()) {
            throw IllegalStateException("File not found: $filePath")
        }
        if (isInsideCacheDir(context, original.absolutePath)) {
            // Already managed by us (e.g. retried prepare) — nothing to copy.
            return PreparedFile(original.absolutePath, original.length(), sourceMimeType)
        }
        return copyFileToCache(context, original, sourceMimeType, deleteSource)
    }

    /**
     * True when re-encoding gained nothing: a JPEG source whose downscaled output is not
     * smaller. Keeping the source avoids a second lossy pass and keeps all its metadata.
     * Other source types stay converted (the format change is wanted), and an unknown
     * source size keeps the output.
     */
    fun shouldKeepSource(sourceMimeType: String?, sourceBytes: Long?, outputBytes: Long): Boolean {
        val type = sourceMimeType?.substringBefore(';')?.trim()?.lowercase()
        if (type != "image/jpeg" || sourceBytes == null) return false
        return outputBytes >= sourceBytes
    }

    private fun resolveMimeType(context: Context, filePath: String, isContentUri: Boolean): String? {
        return if (isContentUri) {
            context.contentResolver.getType(Uri.parse(filePath))
        } else {
            val ext = MimeTypeMap.getFileExtensionFromUrl(filePath)
            if (ext.isNullOrEmpty()) null
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
        }
    }

    private fun openStream(context: Context, filePath: String, isContentUri: Boolean): InputStream {
        return if (isContentUri) {
            context.contentResolver.openInputStream(Uri.parse(filePath))
                ?: throw IllegalStateException("Cannot open $filePath")
        } else {
            File(Uri.parse(filePath).path ?: filePath).inputStream()
        }
    }

    /**
     * Returns null (never throws) when the source can't be decoded as an image, or when
     * [shouldKeepSource] says re-encoding gained nothing, so callers fall back to the
     * copy/original path instead of crashing on a mislabeled "image".
     */
    private fun tryDownscaleImage(
        context: Context,
        filePath: String,
        isContentUri: Boolean,
        sourceMimeType: String?,
        maxDimension: Int,
        quality: Double
    ): PreparedFile? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(context, filePath, isContentUri).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            val sampledBitmap = openStream(context, filePath, isContentUri).use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            val orientation = readOrientation(context, filePath, isContentUri)
            val rotatedBitmap = applyExifRotation(sampledBitmap, orientation)
            val scaledBitmap = scaleToMaxDimension(rotatedBitmap, maxDimension)

            val outputFile = uniqueFile(context, filePath, ".jpg")
            FileOutputStream(outputFile).use { out ->
                val quality100 = Math.round(quality * 100).toInt().coerceIn(1, 100)
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality100, out)
            }
            val outputWidth = scaledBitmap.width
            val outputHeight = scaledBitmap.height
            if (scaledBitmap !== rotatedBitmap) scaledBitmap.recycle()
            if (rotatedBitmap !== sampledBitmap) rotatedBitmap.recycle()
            sampledBitmap.recycle()

            copyExif(context, filePath, isContentUri, outputFile, outputWidth, outputHeight)

            val sourceBytes = sourceLength(context, filePath, isContentUri)
            if (shouldKeepSource(sourceMimeType, sourceBytes, outputFile.length())) {
                outputFile.delete()
                return null
            }

            PreparedFile(outputFile.absolutePath, outputFile.length(), "image/jpeg")
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * The upload URL is signed for the final file size, so callers must read the length
     * after this runs. A failure here is logged and swallowed: a photo without metadata
     * is better than no photo, and the downscaled file is left untouched.
     */
    private fun copyExif(
        context: Context,
        filePath: String,
        isContentUri: Boolean,
        output: File,
        width: Int,
        height: Int
    ) {
        try {
            val source = openStream(context, filePath, isContentUri).use { ExifInterface(it) }
            val target = ExifInterface(output.absolutePath)
            ExifTags.PRESERVED.forEach { tag ->
                source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
            }
            target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifTags.ORIENTATION_AFTER_ROTATION.toString())
            target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            target.saveAttributes()
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy EXIF metadata: ${e.javaClass.simpleName}")
        }
    }

    /** Source length without reading the content; null when the provider does not report it. */
    private fun sourceLength(context: Context, filePath: String, isContentUri: Boolean): Long? {
        return try {
            if (!isContentUri) {
                return File(Uri.parse(filePath).path ?: filePath).length().takeIf { it > 0 }
            }
            val uri = Uri.parse(filePath)
            val fromDescriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            if (fromDescriptor != null && fromDescriptor >= 0) return fromDescriptor
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val longestSide = maxOf(width, height)
        while (longestSide / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun readOrientation(context: Context, filePath: String, isContentUri: Boolean): Int {
        return try {
            openStream(context, filePath, isContentUri).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            bitmap
        }
    }

    private fun scaleToMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longestSide.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun copyContentUriToCache(context: Context, filePath: String, mimeType: String?): PreparedFile {
        val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val outputFile = uniqueFile(context, filePath, extension?.let { ".$it" } ?: "")
        openStream(context, filePath, true).use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output, bufferSize = 8 * 1024)
            }
        }
        return PreparedFile(outputFile.absolutePath, outputFile.length(), mimeType)
    }

    /**
     * Copies (or moves, when [deleteSource] is true — e.g. compressor output we own)
     * a plain file into the managed cache subdir so it survives until `acknowledge`
     * and is covered by the orphan sweep. Move is attempted as a cheap same-volume
     * rename first; falls back to copy+delete.
     */
    private fun copyFileToCache(
        context: Context,
        source: File,
        mimeType: String?,
        deleteSource: Boolean
    ): PreparedFile {
        val outputFile = uniqueFile(context, source.absolutePath, "")
        if (deleteSource && source.renameTo(outputFile)) {
            return PreparedFile(outputFile.absolutePath, outputFile.length(), mimeType)
        }
        source.inputStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output, bufferSize = 8 * 1024)
            }
        }
        if (deleteSource) {
            source.delete()
        }
        return PreparedFile(outputFile.absolutePath, outputFile.length(), mimeType)
    }

    private fun uniqueFile(context: Context, originalPath: String, forcedExtension: String): File {
        val originalName = Uri.parse(originalPath).lastPathSegment ?: "file"
        val baseName = originalName.substringBeforeLast('.').ifBlank { "file" }
        val suffix = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val extension = forcedExtension.ifEmpty {
            val originalExt = originalName.substringAfterLast('.', "")
            if (originalExt.isNotEmpty()) ".$originalExt" else ""
        }
        return File(cacheDir(context), "$baseName-$suffix$extension")
    }
}
