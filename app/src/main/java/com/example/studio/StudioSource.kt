package com.example.studio

import android.graphics.Bitmap
import android.net.Uri

enum class StudioSourceType {
    CAMERA,
    IMAGE
}

sealed class StudioSource {
    abstract val name: String
    abstract val type: StudioSourceType

    object Camera : StudioSource() {
        override val name: String = "Camera"
        override val type: StudioSourceType = StudioSourceType.CAMERA
        override fun toString(): String = "StudioSource.Camera"
    }

    data class Image(
        val uri: Uri? = null,
        val bitmap: Bitmap? = null,
        override val name: String = "Image",
        val nv12Cache: ByteArray? = null,
        val nv21Cache: ByteArray? = null
    ) : StudioSource() {
        val activeFrameCache: ByteArray? get() = nv12Cache ?: nv21Cache
        override val type: StudioSourceType = StudioSourceType.IMAGE
        override fun toString(): String = "StudioSource.Image(name=$name, uri=$uri)"
    }
}
