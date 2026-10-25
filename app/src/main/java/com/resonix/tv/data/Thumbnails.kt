package com.resonix.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Video preview pictures: taken from the movie once, then kept on disk and in memory. */
object Thumbnails {
    private const val WIDTH = 480
    private val memory = LruCache<String, Bitmap>(24)

    suspend fun load(context: Context, uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(uriString)?.let { return@withContext it }

        val file = File(directory(context), uriString.hashCode().toString(16) + "_" + uriString.length + ".jpg")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let {
                memory.put(uriString, it)
                return@withContext it
            }
        }
        val bitmap = extract(context, Uri.parse(uriString)) ?: return@withContext null
        runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
        memory.put(uriString, bitmap)
        bitmap
    }

    fun clear(context: Context) {
        memory.evictAll()
        directory(context).listFiles()?.forEach { it.delete() }
    }

    private fun directory(context: Context): File = File(context.cacheDir, "thumbs").also { it.mkdirs() }

    private fun extract(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") retriever.setDataSource(uri.path.orEmpty()) else retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val atUs = if (durationMs > 0) durationMs * 1000L / 10L else 5_000_000L
            val frame = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val height = (frame.height * WIDTH.toFloat() / frame.width).toInt().coerceAtLeast(1)
            if (frame.width <= WIDTH) frame else Bitmap.createScaledBitmap(frame, WIDTH, height, true)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** The preview picture for a movie (null while it is being made, or if there is none). */
@Composable
fun rememberThumbnail(uriString: String): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = uriString) {
        value = Thumbnails.load(context, uriString)?.asImageBitmap()
    }
    return image
}
