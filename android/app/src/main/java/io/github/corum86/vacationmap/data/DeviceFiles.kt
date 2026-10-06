package io.github.corum86.vacationmap.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

// Reading and writing the user's files: photos they pick, images and data
// they export. Everything here blocks, so it all runs on the IO dispatcher.

/**
 * Downscale a picked or captured photo to a JPEG `data:` URL small enough to
 * keep inside the data (~100–200 KB instead of a multi-megabyte camera
 * original), as the web app does: that is what lets the traveller's own
 * photos sync between devices.
 */
suspend fun readPhotoAsDataUrl(context: Context, uri: Uri, maxSide: Int = 1280, quality: Int = 75): String =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        fun open() = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")

        // decode at the largest power-of-two reduction that still leaves enough pixels
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IOException("Cannot decode image")

        // cameras store the sensor's orientation and say in EXIF how to turn it
        val orientation = open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val matrix = Matrix()
        val scale = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        matrix.postScale(scale, scale)
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
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val bytes = ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

/** Save an image to the device's Pictures, where the gallery finds it. */
suspend fun saveImageToPictures(context: Context, bitmap: Bitmap, fileName: String): Uri = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Vacation Map")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: throw IOException("Cannot create image")
    try {
        (resolver.openOutputStream(uri) ?: throw IOException("Cannot write image")).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Cannot encode image")
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        uri
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}

suspend fun readTextFile(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")).bufferedReader().use { it.readText() }
}

suspend fun writeTextFile(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    // "wt": replace what is there rather than overwrite its beginning
    (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")).bufferedWriter().use { it.write(text) }
}
