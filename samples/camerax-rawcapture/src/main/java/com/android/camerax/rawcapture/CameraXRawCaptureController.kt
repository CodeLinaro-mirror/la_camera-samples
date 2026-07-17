/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.camerax.rawcapture

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "CameraXRawCapture"

@Composable
fun rememberCameraXRawCaptureController(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    onCaptured: (dngUri: Uri, jpegUri: Uri) -> Unit,
    onCapabilitiesReady: (isFullSensorSupported: Boolean, pixelBinLabel: String, fullSensorLabel: String) -> Unit,
    onUnsupported: () -> Unit,
): CameraXRawCaptureController {
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    val latestOnCapabilitiesReady by rememberUpdatedState(onCapabilitiesReady)
    val latestOnUnsupported by rememberUpdatedState(onUnsupported)
    return remember(context, lifecycleOwner) {
        CameraXRawCaptureController(
            context,
            lifecycleOwner,
            onCaptured = { dng, jpeg -> latestOnCaptured(dng, jpeg) },
            onCapabilitiesReady = { isFullSupported, pixelBin, fullSensor ->
                latestOnCapabilitiesReady(isFullSupported, pixelBin, fullSensor)
            },
            onUnsupported = { latestOnUnsupported() },
        )
    }
}

/**
 * Captures a RAW (DNG) frame and a companion JPEG in one shot with CameraX. After resolving the back
 * camera it checks [ImageCapture.getImageCaptureCapabilities]; if the camera does not advertise
 * [ImageCapture.OUTPUT_FORMAT_RAW_JPEG] it reports [onUnsupported] instead of binding. Supports
 * toggling between standard pixel-binned mode and full sensor native resolution mode via Camera2Interop.
 */
@Stable
class CameraXRawCaptureController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onCaptured: (dngUri: Uri, jpegUri: Uri) -> Unit,
    private val onCapabilitiesReady: (isFullSensorSupported: Boolean, pixelBinLabel: String, fullSensorLabel: String) -> Unit,
    private val onUnsupported: () -> Unit,
) {
    private val appContext = context.applicationContext

    private val providerScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    var surfaceRequest: SurfaceRequest? by mutableStateOf(null)
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var currentMode: RawSensorMode = RawSensorMode.PIXEL_BIN
    private var isFullSensorSupported: Boolean = false

    // The dual-format callback fires twice; hold each URI until its partner arrives.
    private var pendingDngUri: Uri? = null
    private var pendingJpegUri: Uri? = null

    private val preview =
        Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }

    private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    fun openCamera(mode: RawSensorMode = currentMode) {
        currentMode = mode
        providerScope.launch {
            val provider = ProcessCameraProvider.getInstance(appContext).await()
            cameraProvider = provider
            try {
                val cameraInfo = cameraSelector.filter(provider.availableCameraInfos).firstOrNull()
                if (cameraInfo == null) {
                    onUnsupported()
                    return@launch
                }

                val caps = ImageCapture.getImageCaptureCapabilities(cameraInfo)
                if (!caps.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)) {
                    onUnsupported()
                    return@launch
                }

                val eval = evaluateSensorCapabilities(cameraInfo)
                isFullSensorSupported = eval.isFullSensorSupported
                onCapabilitiesReady(eval.isFullSensorSupported, eval.pixelBinLabel, eval.fullSensorLabel)

                bindImageCapture(provider, mode)
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed", e)
                onUnsupported()
            }
        }
    }

    fun setSensorMode(mode: RawSensorMode) {
        if (currentMode == mode) return
        currentMode = mode
        val provider = cameraProvider ?: return
        bindImageCapture(provider, mode)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindImageCapture(
        provider: ProcessCameraProvider,
        mode: RawSensorMode,
    ) {
        val captureBuilder =
            ImageCapture
                .Builder()
                .setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)

        if (mode == RawSensorMode.FULL_SENSOR && isFullSensorSupported) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Camera2Interop.Extender(captureBuilder).setCaptureRequestOption(
                    CaptureRequest.SENSOR_PIXEL_MODE,
                    CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION,
                )
            }
            captureBuilder.setResolutionSelector(
                ResolutionSelector
                    .Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build(),
            )
        }

        val capture = captureBuilder.build()
        imageCapture = capture

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, capture)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun evaluateSensorCapabilities(cameraInfo: CameraInfo): SensorCapabilities {
        val camera2Info = Camera2CameraInfo.from(cameraInfo)
        val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val logicalChars =
            try {
                cameraManager?.getCameraCharacteristics(camera2Info.cameraId)
            } catch (e: Exception) {
                null
            }

        val caps =
            logicalChars?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: camera2Info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        var fullSupported =
            caps?.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR,
            ) == true

        val streamMap =
            logicalChars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: camera2Info.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val defaultRawSize =
            streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
        val pixelBinLabel = defaultRawSize?.let { formatMegapixels(it.width, it.height) } ?: ""

        var maxRawSize =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val maxResStreamMap =
                    logicalChars?.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION,
                    ) ?: camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION,
                    )
                maxResStreamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull {
                    it.width * it.height
                }
            } else {
                null
            }

        if (maxRawSize != null) {
            fullSupported = true
        }

        // On Google Pixel and multi-camera devices, the default logical camera ID ("0") might hide
        // ULTRA_HIGH_RESOLUTION_SENSOR. Query the underlying physical camera IDs to discover native 50MP streams.
        if (!fullSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && logicalChars != null && cameraManager != null) {
            val physicalIds = logicalChars.physicalCameraIds
            for (physicalId in physicalIds) {
                try {
                    val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                    val physicalCaps =
                        physicalChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    val physicalHasUltra =
                        physicalCaps?.contains(
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR,
                        ) == true

                    val physicalMaxMap =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            physicalChars.get(
                                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION,
                            )
                        } else {
                            null
                        }
                    val physicalMaxSize =
                        physicalMaxMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull {
                            it.width * it.height
                        }

                    if (physicalHasUltra || physicalMaxSize != null) {
                        fullSupported = true
                        if (maxRawSize == null ||
                            (
                                physicalMaxSize != null &&
                                    physicalMaxSize.width * physicalMaxSize.height > maxRawSize.width * maxRawSize.height
                            )
                        ) {
                            maxRawSize = physicalMaxSize
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to query physical camera $physicalId", e)
                }
            }
        }

        val fullSensorLabel = maxRawSize?.let { formatMegapixels(it.width, it.height) } ?: ""

        return SensorCapabilities(
            isFullSensorSupported = fullSupported,
            pixelBinLabel = pixelBinLabel,
            fullSensorLabel = fullSensorLabel,
        )
    }

    private fun formatMegapixels(
        width: Int,
        height: Int,
    ): String {
        val mp = (width.toLong() * height) / 1_000_000.0
        return if (mp >= 10.0 && mp % 1.0 < 0.05) {
            String.format(Locale.US, "%.0f MP", mp)
        } else {
            String.format(Locale.US, "%.1f MP", mp)
        }
    }

    fun updateTargetRotation(rotation: Int) {
        imageCapture?.targetRotation = rotation
        preview.targetRotation = rotation
    }

    fun takePicture() {
        val capture = imageCapture ?: return
        pendingDngUri = null
        pendingJpegUri = null

        val timestamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())
        val dngOptions = buildOutputOptions("RAW_$timestamp.dng", "image/x-adobe-dng")
        val jpegOptions = buildOutputOptions("IMG_$timestamp.jpg", "image/jpeg")

        capture.takePicture(
            dngOptions,
            jpegOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri ?: return
                    // The callback fires once per format; route by the saved image format.
                    if (outputFileResults.imageFormat == ImageFormat.JPEG) {
                        pendingJpegUri = uri
                    } else {
                        pendingDngUri = uri
                    }
                    val dng = pendingDngUri
                    val jpeg = pendingJpegUri
                    if (dng != null && jpeg != null) {
                        pendingDngUri = null
                        pendingJpegUri = null
                        onCaptured(dng, jpeg)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "RAW/JPEG capture failed: ${exception.message}", exception)
                }
            },
        )
    }

    private fun buildOutputOptions(
        displayName: String,
        mimeType: String,
    ): ImageCapture.OutputFileOptions {
        val contentValues =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                }
            }
        return ImageCapture.OutputFileOptions
            .Builder(
                appContext.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues,
            ).build()
    }

    fun closeCamera() {
        cameraProvider?.unbindAll()
    }

    fun release() {
        closeCamera()
        providerScope.cancel()
        cameraExecutor.shutdown()
    }
}

private data class SensorCapabilities(
    val isFullSensorSupported: Boolean,
    val pixelBinLabel: String,
    val fullSensorLabel: String,
)
