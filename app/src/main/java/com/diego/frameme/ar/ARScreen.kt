package com.diego.frameme.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.diego.frameme.domain.FrameDimensions
import com.diego.frameme.ui.ARViewModel
import com.diego.frameme.ui.ARViewModel.Event
import com.diego.frameme.ui.components.CustomizeSheet
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.RotationsOrder
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.PlaneDiscoveryGuide
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.math.toRotation
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File

/**
 * The whole AR experience: vertical-wall scanning with coaching, tap-to-place
 * against walls, wall-constrained gestures, live frame customization and
 * gallery snapshots.
 *
 * ARCore objects ([Anchor], [Frame]) live here as Compose state — the
 * ViewModel stays free of session types. Every ARCore callback below runs on
 * the AR/render thread as documented by SceneView.
 */
@Composable
fun ARScreen(viewModel: ARViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rootView = LocalView.current
    val config by viewModel.frameConfig.collectAsStateWithLifecycle()
    val artwork by viewModel.artwork.collectAsStateWithLifecycle()
    val isSelected by viewModel.isFrameSelected.collectAsStateWithLifecycle()
    val yaw by viewModel.yawDegrees.collectAsStateWithLifecycle()
    val tracking by viewModel.tracking.collectAsStateWithLifecycle()
    val photoUri by viewModel.selectedPhotoUri.collectAsStateWithLifecycle()

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ---- AR placement state ----------------------------------------------------
    var anchor by remember { mutableStateOf<Anchor?>(null) }
    var wallNormal by remember { mutableStateOf(Float3(0f, 0f, 1f)) }
    var anchorRotation by remember { mutableStateOf(WallPlacement.Quat.identity) }
    var latestArFrame by remember { mutableStateOf<Frame?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var pendingSnapshot by remember { mutableStateOf<File?>(null) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is Event.SnapshotSaved -> snackbarHostState.showSnackbar("Saved to Pictures/FrameMe")
                is Event.SnapshotFailed -> snackbarHostState.showSnackbar(event.reason)
                Event.PhotoFailed -> snackbarHostState.showSnackbar("Couldn't load that photo")
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.setPhoto(uri)
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val file = pendingSnapshot
        pendingSnapshot = null
        if (granted && file != null) {
            viewModel.saveSnapshotToGallery(file)
        } else if (!granted) {
            scope.launch { snackbarHostState.showSnackbar("Storage permission denied") }
        }
    }

    val takeSnapshot: () -> Unit = takeSnapshot@{
        scope.launch {
            val file = captureSurfaceSnapshot(context, rootView)
            if (file == null) {
                snackbarHostState.showSnackbar("Nothing to capture yet")
                return@launch
            }
            val legacyStorageNeeded = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            if (legacyStorageNeeded) {
                pendingSnapshot = file
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                viewModel.saveSnapshotToGallery(file)
            }
        }
    }

    fun tryPlaceFrame(event: MotionEvent) {
        val frame = latestArFrame ?: return
        val hit = frame.hitTest(event).firstOrNull { WallPlacement.isPlaceableWallHit(it) } ?: return
        val newAnchor = hit.createAnchorOrNull() ?: return
        val pose = hit.hitPose
        anchor?.detach()
        wallNormal = WallPlacement.towardCamera(
            normal = Float3(pose.getYAxis()[0], pose.getYAxis()[1], pose.getYAxis()[2]),
            cameraPosition = cameraNode.worldPosition,
            surfacePosition = Float3(pose.tx(), pose.ty(), pose.tz()),
        )
        anchorRotation = WallPlacement.quatFromXYZW(pose.getRotationQuaternion())
        anchor = newAnchor
        viewModel.resetYaw()
        viewModel.selectFrame(false)
        scope.launch { snackbarHostState.showSnackbar("Frame hung — drag to slide, two fingers to twist") }
    }

    fun handleSingleTap(event: MotionEvent, node: Node?) {
        if (node != null && node.name == FRAME_NODE_TAG) {
            viewModel.toggleFrameSelection()
        } else {
            viewModel.selectFrame(false)
            if (anchor == null) tryPlaceFrame(event)
        }
    }

    var twistAngle by remember { mutableStateOf<Float?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            // Strictly vertical surfaces: the app is about hanging frames on walls.
            planeFindingMode = Config.PlaneFindingMode.VERTICAL,
            depthMode = Config.DepthMode.AUTOMATIC,
            // Show the wall detection marks until the frame is placed (and again
            // while dragging it to a new spot); detection itself never stops.
            planeRenderer = anchor == null || isDragging,
            cameraNode = cameraNode,
            // ENVIRONMENTAL_HDR light estimation stays on by default, so the
            // frame's PBR materials pick up the room illumination.
            onSessionUpdated = { session, frame ->
                latestArFrame = frame
                val isTracking = frame.camera.trackingState == TrackingState.TRACKING
                val verticalPlaneTracked = session.getAllTrackables(Plane::class.java).any {
                    it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.VERTICAL
                }
                viewModel.onSessionUpdated(
                    sessionReady = true,
                    isTracking = isTracking,
                    verticalPlaneTracked = verticalPlaneTracked,
                )
            },
            onTrackingFailureChanged = { reason ->
                trackingFailure = reason
                viewModel.onTrackingFailureHint(trackingFailureHint(reason))
            },
            onSessionFailed = { exception ->
                scope.launch { snackbarHostState.showSnackbar("AR session failed: ${exception.message}") }
            },
            onARCoreAvailability = { availability ->
                val message = when (availability) {
                    ARCoreAvailability.Unsupported -> "This device isn't ARCore-compatible"
                    ARCoreAvailability.NotInstalled -> "Install Google ARCore to continue"
                    ARCoreAvailability.NeedsUpdate -> "Update Google ARCore to continue"
                    ARCoreAvailability.CheckFailed -> "Couldn't verify ARCore support"
                    ARCoreAvailability.SessionFailed -> "AR session failed to start"
                    else -> null
                }
                if (message != null) scope.launch { snackbarHostState.showSnackbar(message) }
            },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { event, node -> handleSingleTap(event, node) },
                onMoveBegin = { _, _, node ->
                    if (node != null && node.name == FRAME_NODE_TAG) {
                        isDragging = true
                        viewModel.selectFrame(true)
                    }
                },
                onMoveEnd = { _, _, _ -> isDragging = false },
            ),
            onTouchEvent = { event, _ ->
                // Manual two-finger twist about the wall normal — built-in node
                // rotation is disabled so the frame can never tilt off the wall.
                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (event.pointerCount == 2) {
                            twistAngle = twoPointerAngle(event)
                            viewModel.selectFrame(true)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val currentAngle = twoPointerAngle(event)
                        val previous = twistAngle
                        if (previous != null && event.pointerCount >= 2) {
                            viewModel.addYaw(TWIST_SIGN * WallPlacement.twistDeltaDegrees(previous, currentAngle))
                        }
                        twistAngle = currentAngle
                    }
                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> twistAngle = null
                }
                false
            },
        ) {
            anchor?.let { placed ->
                AnchorNode(
                    anchor = placed,
                    apply = {
                        isEditable = true
                        isPositionEditable = true
                        isRotationEditable = false
                        isScaleEditable = false
                    },
                ) {
                    val worldQuaternion = WallPlacement.frameWorldQuaternion(
                        forward = wallNormal,
                        yawDegrees = yaw,
                    )
                    val localQuaternion = WallPlacement.localRotation(anchorRotation, worldQuaternion)
                    FrameSceneContent(
                        dimensions = FrameDimensions.fromConfig(config),
                        config = config,
                        rootRotation = Quaternion(
                            localQuaternion.x,
                            localQuaternion.y,
                            localQuaternion.z,
                            localQuaternion.w,
                        ).toRotation(RotationsOrder.ZYX),
                        artwork = artwork,
                        materialLoader = materialLoader,
                    )
                }
            }
        }

        // Coaching overlay while no wall is found yet, or tracking degrades.
        PlaneDiscoveryGuide(
            cameraReady = tracking.sessionReady,
            isTracking = tracking.isTracking,
            anyPlaneTracked = tracking.verticalPlaneTracked,
            trackingFailureReason = trackingFailure ?: TrackingFailureReason.NONE,
            bottomClearance = if (sheetOpen) 340.dp else 130.dp,
        )

        if (anchor == null && tracking.verticalPlaneTracked) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            ) {
                Text(
                    text = "Tap the wall to hang the frame",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = takeSnapshot) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = "Take snapshot")
            }
            FilledTonalIconButton(onClick = { sheetOpen = true }) {
                Icon(Icons.Filled.Tune, contentDescription = "Customize frame")
            }
        }

        if (isSelected) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { viewModel.resetYaw() }) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("  Straighten")
                }
            }
        }

        CustomizeSheet(
            onDismissRequest = { sheetOpen = false },
            config = config,
            selectedPhotoUri = photoUri,
            framePlaced = anchor != null,
            onConfigChange = { newConfig -> viewModel.updateConfig { newConfig } },
            onPickPhoto = {
                pickPhoto.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onDeleteFrame = {
                anchor?.detach()
                anchor = null
                viewModel.selectFrame(false)
                sheetOpen = false
            },
        )

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Every node of the frame tree carries this tag, so node picks identify it. */
const val FRAME_NODE_TAG = "frameme_frame"

/** +1: screen-clockwise twist == clockwise yaw in the wall plane. */
const val TWIST_SIGN = 1f

private fun twoPointerAngle(event: MotionEvent): Float {
    val dx = event.getX(0) - event.getX(1)
    val dy = event.getY(0) - event.getY(1)
    return Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
}

private fun trackingFailureHint(reason: TrackingFailureReason?): String? = when (reason) {
    TrackingFailureReason.BAD_STATE -> "AR session error — restart the app"
    TrackingFailureReason.INSUFFICIENT_LIGHT -> "Not enough light — try a brighter area"
    TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast — slow down"
    TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at a more textured wall — edges and details help"
    TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable — close other camera apps"
    TrackingFailureReason.NONE, null -> null
}

/**
 * Grabs the live AR surface (camera feed + rendered frame) with [PixelCopy],
 * which is what the SceneRecorder would do — SceneView 4.63.0 doesn't ship one.
 */
private suspend fun captureSurfaceSnapshot(context: Context, rootView: View): File? {
    val surfaceView = rootView.findSurfaceView() ?: return null
    if (surfaceView.width <= 0 || surfaceView.height <= 0) return null
    val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
    val success = suspendCancellableCoroutine { continuation ->
        PixelCopy.request(
            surfaceView,
            bitmap,
            { result -> continuation.resume(result == PixelCopy.SUCCESS) },
            Handler(Looper.getMainLooper()),
        )
    }
    if (!success) return null
    return runCatching {
        File(context.cacheDir, "frameme_snapshot_${System.currentTimeMillis()}.jpg").apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
    }.getOrNull()
}

private fun View.findSurfaceView(): SurfaceView? = when (this) {
    is SurfaceView -> this
    is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findSurfaceView() }
    else -> null
}
