@file:Suppress("DEPRECATION")

package local.oss.chronicle.views

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.databinding.BindingAdapter
import com.facebook.cache.common.CacheKey
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.generic.GenericDraweeHierarchy
import com.facebook.drawee.view.DraweeView
import com.facebook.imagepipeline.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import local.oss.chronicle.R
import local.oss.chronicle.application.Injector
import timber.log.Timber

@BindingAdapter(value = ["srcRounded", "serverConnected", "libraryId"], requireAll = false)
fun bindImageRounded(
    draweeView: DraweeView<GenericDraweeHierarchy>,
    src: String?,
    serverConnected: Boolean,
    libraryId: String?,
) {
    if ((draweeView.context as Activity).isDestroyed) {
        return
    }
    if (src.isNullOrBlank() || src.equals("null", ignoreCase = true)) {
        draweeView.setImageURI(Uri.EMPTY)
        return
    }

    val imageSize =
        draweeView.resources.getDimension(R.dimen.currently_playing_artwork_max_size).toInt()
    val config = Injector.get().plexConfig()

    // If libraryId is provided, use library-aware thumbnail URL resolution
    if (!libraryId.isNullOrEmpty()) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val url = config.makeThumbUriForLibrary(src ?: "", libraryId)
                if (url == Uri.EMPTY) {
                    draweeView.setImageURI(Uri.EMPTY)
                    return@launch
                }
                val request = ImageRequest.fromUri(url)
                val controller =
                    Fresco.newDraweeControllerBuilder()
                        .setImageRequest(request)
                        .setOldController(draweeView.controller)
                        .build()
                draweeView.controller = controller
            } catch (e: Exception) {
                Timber.e(e, "Failed to load library-aware thumbnail for libraryId=$libraryId")
                // Fallback to global config
                loadWithGlobalConfig(draweeView, src, imageSize, config)
            }
        }
    } else {
        // Fallback to global PlexConfig URL (for collections or when libraryId not available)
        loadWithGlobalConfig(draweeView, src, imageSize, config)
    }
}

private fun loadWithGlobalConfig(
    draweeView: DraweeView<GenericDraweeHierarchy>,
    src: String?,
    imageSize: Int,
    config: local.oss.chronicle.data.sources.plex.PlexConfig,
) {
    val url =
        config.toServerString("photo/:/transcode?width=$imageSize&height=$imageSize&url=$src")
            .toUri()

    val request = ImageRequest.fromUri(url)
    val controller =
        Fresco.newDraweeControllerBuilder()
            .setImageRequest(request)
            .setOldController(draweeView.controller)
            .build()
    draweeView.controller = controller
}

/**
 * A [CacheKey] which uses the query (everything after ?) in the URL as the key,
 * as opposed to the entire URL, so that caching will work regardless of the route
 * connecting the user to the server
 */
class UrlQueryCacheKey(private val url: Uri?) : CacheKey {
    private val cacheQuery = sanitizedQuery(url)

    override fun containsUri(uri: Uri): Boolean {
        return sanitizedQuery(uri)?.contains(cacheQuery.orEmpty()) ?: false
    }

    // Seems to be primarily used for debugging
    override fun getUriString() = cacheQuery.orEmpty()

    override fun isResourceIdForDebugging() = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UrlQueryCacheKey

        return cacheQuery == other.cacheQuery
    }

    override fun hashCode(): Int {
        return cacheQuery?.hashCode() ?: 0
    }

    override fun toString(): String {
        return cacheQuery.orEmpty()
    }

    private companion object {
        fun sanitizedQuery(uri: Uri?): String? {
            if (uri == null) return null
            return uri.queryParameterNames
                .filterNot { name ->
                    name.equals("api_key", ignoreCase = true) || name.equals("X-Plex-Token", ignoreCase = true)
                }
                .sorted()
                .flatMap { name -> uri.getQueryParameters(name).map { value -> "${Uri.encode(name)}=${Uri.encode(value)}" } }
                .joinToString("&")
        }
    }
}

// NOTE: this will not work for Android versions HoneyComb and below, and DataBinding overrides the
// tag set on all outermost layouts in a data bound layout xml
@RequiresApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
@BindingAdapter("specialTag")
fun bindTag(
    view: View,
    o: Any,
) {
    view.tag = o
}
