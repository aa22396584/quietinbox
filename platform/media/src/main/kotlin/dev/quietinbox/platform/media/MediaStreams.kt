package dev.quietinbox.platform.media

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place a `content://` stream is opened. It is its own type so that the failure mapping
 * around it can be exercised without a device: `platform/media` shipped with no test at all, which
 * is how a dead provider came to be reported as "media too large" (QI-MEDIA-013).
 */
@Singleton
open class MediaStreams @Inject constructor(@ApplicationContext private val context: Context) {
    open fun open(uri: Uri): InputStream? = context.contentResolver.openInputStream(uri)
}
