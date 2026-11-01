package com.diego.frameme.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.diego.frameme.config.FrameConfig
import com.diego.frameme.config.FramePreset
import com.diego.frameme.domain.FrameDimensions
import com.diego.frameme.domain.ImageCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single source of truth for everything that is not the ARCore session itself:
 * the frame configuration (all centimeters), the selected photo (decoded by
 * Coil, cropped to the mat-window aspect) and AR-tracking UI flags.
 */
class ARViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface Event {
        data class SnapshotSaved(val uri: Uri) : Event
        data class SnapshotFailed(val reason: String) : Event
        data object PhotoFailed : Event
    }

    data class TrackingUi(
        val sessionReady: Boolean = false,
        val isTracking: Boolean = false,
        /** A wall is available: a tracked vertical plane, or depth sees one. */
        val wallTracked: Boolean = false,
        val failureHint: String? = null,
    ) {
        val showCoach: Boolean
            get() = !sessionReady || !wallTracked || failureHint != null
    }

    private val _frameConfig = MutableStateFlow(FrameConfig.DEFAULT)
    val frameConfig: StateFlow<FrameConfig> = _frameConfig.asStateFlow()

    private val _selectedPhotoUri = MutableStateFlow<Uri?>(null)
    val selectedPhotoUri: StateFlow<Uri?> = _selectedPhotoUri.asStateFlow()

    private val _sourceBitmap = MutableStateFlow<Bitmap?>(null)

    /**
     * The artwork bitmap, always cover-cropped to the CURRENT mat-window
     * aspect. Recomputed whenever the photo or any dimension changes, so the
     * texture aspect always matches the physical quad (no distortion).
     */
    val artwork: StateFlow<Bitmap?> = combine(
        _sourceBitmap,
        _frameConfig.map { FrameDimensions.fromConfig(it).artworkAspect }.distinctUntilChanged(),
    ) { source, aspect ->
        source?.let { ImageCrop.centerCrop(it, aspect) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _isFrameSelected = MutableStateFlow(false)
    val isFrameSelected: StateFlow<Boolean> = _isFrameSelected.asStateFlow()

    /** User twist of the frame within the wall plane, degrees, kept in (-180, 180]. */
    private val _yawDegrees = MutableStateFlow(0f)
    val yawDegrees: StateFlow<Float> = _yawDegrees.asStateFlow()

    private val _tracking = MutableStateFlow(TrackingUi())
    val tracking: StateFlow<TrackingUi> = _tracking.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun updateConfig(transform: (FrameConfig) -> FrameConfig) {
        _frameConfig.update(transform)
    }

    fun setPreset(preset: FramePreset) = updateConfig {
        it.copy(widthCm = preset.widthCm, heightCm = preset.heightCm)
    }

    fun selectFrame(selected: Boolean) {
        _isFrameSelected.value = selected
    }

    fun toggleFrameSelection() {
        _isFrameSelected.update { !it }
    }

    fun addYaw(deltaDegrees: Float) {
        _yawDegrees.update { current ->
            var next = current + deltaDegrees
            while (next > 180f) next -= 360f
            while (next <= -180f) next += 360f
            next
        }
    }

    fun resetYaw() {
        _yawDegrees.value = 0f
    }

    fun onSessionUpdated(sessionReady: Boolean, isTracking: Boolean, wallTracked: Boolean) {
        _tracking.update {
            it.copy(
                sessionReady = sessionReady,
                isTracking = isTracking,
                wallTracked = wallTracked,
            )
        }
    }

    fun onTrackingFailureHint(hint: String?) {
        _tracking.update { it.copy(failureHint = hint) }
    }

    /**
     * Decodes the picked photo with Coil off the main thread. Hardware bitmaps
     * are disallowed because the pixels must be uploaded into a Filament texture.
     */
    fun setPhoto(uri: Uri?) {
        _selectedPhotoUri.value = uri
        _sourceBitmap.value = null
        if (uri == null) return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) { decodePhoto(uri) }
            if (bitmap != null) {
                _sourceBitmap.value = bitmap
            } else {
                _events.tryEmit(Event.PhotoFailed)
            }
        }
    }

    private suspend fun decodePhoto(uri: Uri): Bitmap? = try {
        val context = getApplication<Application>()
        val request = ImageRequest.Builder(context)
            .data(uri)
            .allowHardware(false)
            .size(2048)
            .build()
        (SingletonImageLoader.get(context).execute(request).image as? BitmapImage)?.bitmap
    } catch (t: Throwable) {
        null
    }

    /**
     * Copies a snapshot file produced by [SceneRecorder.savePhoto] into the
     * public gallery under Pictures/FrameMe. Needs WRITE_EXTERNAL_STORAGE at
     * runtime on API ≤ 28 (handled by the caller).
     */
    fun saveSnapshotToGallery(snapshot: File) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { insertIntoMediaStore(snapshot) }.getOrNull() }
            if (uri != null) {
                _events.tryEmit(Event.SnapshotSaved(uri))
            } else {
                _events.tryEmit(Event.SnapshotFailed("Could not write to gallery"))
            }
        }
    }

    private fun insertIntoMediaStore(snapshot: File): Uri? {
        val resolver = getApplication<Application>().contentResolver
        val details = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "FrameMe_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "FrameMe")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, details) ?: return null
        resolver.openOutputStream(uri)?.use { output ->
            snapshot.inputStream().use { input -> input.copyTo(output) }
        } ?: return null
        return uri
    }
}
