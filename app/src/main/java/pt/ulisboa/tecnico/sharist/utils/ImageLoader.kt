package pt.ulisboa.tecnico.sharist.utils

import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import pt.ulisboa.tecnico.sharist.R

/**
 * Centralized image loading with metered-connection awareness.
 *
 * Resource frugality rules:
 *  - On Wi-Fi       → load automatically when visible
 *  - On metered     → show placeholder; load only on explicit user tap
 *  - Disk cache     → always cache downloaded images (avoid re-download)
 */
object ImageLoader {

    private val diskCacheOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .centerCrop()

    /**
     * Load an image respecting the current connection type.
     *
     * @param imageView  Target view
     * @param url        Remote image URL (may be null)
     * @param placeholder Drawable resource id for the placeholder
     * @param network    NetworkMonitor for connection-type decisions
     * @param onMetered  Optional lambda called when on metered — attach tap listener here
     */
    fun load(
        imageView: ImageView,
        url: String?,
        placeholder: Int,
        network: NetworkMonitor,
        onMetered: ((ImageView) -> Unit)? = null
    ) {
        if (url.isNullOrBlank()) {
            imageView.setImageResource(placeholder)
            return
        }

        when (network.connectionType) {
            ConnectionType.WIFI -> {
                // Free data: load immediately
                Glide.with(imageView.context)
                    .load(url)
                    .apply(diskCacheOptions.placeholder(placeholder))
                    .into(imageView)
            }

            ConnectionType.METERED -> {
                // Metered: show placeholder + overlay hint, load on tap
                imageView.setImageResource(placeholder)
                onMetered?.invoke(imageView)

                imageView.setOnClickListener {
                    imageView.setOnClickListener(null) // one-shot
                    Glide.with(imageView.context)
                        .load(url)
                        .apply(diskCacheOptions)
                        .into(imageView)
                }
            }

            ConnectionType.NONE -> {
                // Offline: try disk cache, fallback to placeholder
                Glide.with(imageView.context)
                    .load(url)
                    .apply(
                        diskCacheOptions
                            .placeholder(placeholder)
                            .onlyRetrieveFromCache(true)  // no network request
                    )
                    .into(imageView)
            }
        }
    }
}
