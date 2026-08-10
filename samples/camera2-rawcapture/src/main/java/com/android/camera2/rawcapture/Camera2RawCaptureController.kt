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
package com.android.camera2.rawcapture

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.android.camera.core.camera2.BaseCamera2Controller
import com.android.camera.core.media.MediaStoreSaver
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "Camera2RawCapture"
private const val PREVIEW_WIDTH = 1920
private const val PREVIEW_HEIGHT = 1080

@Composable
fun rememberCamera2RawCaptureController(
    context: Context,
    isFrontCamera: Boolean,
    onDngSaved: (uri: Uri, rotationDegrees: Int) -> Unit,
    onCapabilitiesReady: (isFullSensorSupported: Boolean, pixelBinLabel: String, fullSensorLabel: String) -> Unit,
    onUnsupported: () -> Unit,
): Camera2RawCaptureController {
    val latestOnDngSaved by rememberUpdatedState(onDngSaved)
    val latestOnCapabilitiesReady by rememberUpdatedState(onCapabilitiesReady)
    val latestOnUnsupported by rememberUpdatedState(onUnsupported)
    return remember(context, isFrontCamera) {
        Camera2RawCaptureController(
            context,
            isFrontCamera,
            onDngSaved = { uri, rotationDegrees -> latestOnDngSaved(uri, rotationDegrees) },
            onCapabilitiesReady = { isFullSupported, pixelBin, fullSensor ->
                latestOnCapabilitiesReady(isFullSupported, pixelBin, fullSensor)
            },
            onUnsupported = { latestOnUnsupported() },
        )
    }
}

/**
 * Captures a single `RAW_SENSOR` frame and writes it as a DNG via [DngCreator]. The shared
 * open/close/transform plumbing lives in [BaseCamera2Controller];
 * this class gates on the camera's RAW capability, adds a `RAW_SENSOR` [ImageReader], and pairs each
 * captured [Image] with its [TotalCaptureResult] (DngCreator needs both) before saving.
 * Supports toggling between standard pixel-binned mode and full sensor native resolution mode.
 * All work happens on the controller's background handler.
 */
@Stable
class Camera2RawCaptureController(
    context: Context,
    isFrontCamera: Boolean,
    private val onDngSaved: (uri: Uri, rotationDegrees: Int) -> Unit,
    private val onCapabilitiesReady: (isFullSensorSupported: Boolean, pixelBinLabel: String, fullSensorLabel: String) -> Unit,
    private val onUnsupported: () -> Unit,
) : BaseCamera2Controller(context, isFrontCamera) {
    private var rawReader: ImageReader? = null
    private var sensorOrientation: Int = 90

    private var currentMode: RawSensorMode = RawSensorMode.PIXEL_BIN
    private var isFullSensorSupported: Boolean = false
    private var defaultRawSize: Size? = null
    private var maxRawSize: Size? = null
    private var currentPreviewSurface: Surface? = null

    // DngCreator needs the RAW Image and the TotalCaptureResult that produced it; they arrive on two
    // callbacks (both on the background handler), so hold each until its partner is ready.
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null

    override fun onCameraPrepared(characteristics: CameraCharacteristics) {
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val capabilities =
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val rawSupported =
            capabilities.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW,
            )
        if (!rawSupported) {
            onUnsupported()
            return
        }

        val eval = evaluateSensorCapabilities(characteristics)
        isFullSensorSupported = eval.isFullSensorSupported
        defaultRawSize = eval.defaultRawSize
        maxRawSize = eval.maxRawSize
        onCapabilitiesReady(eval.isFullSensorSupported, eval.pixelBinLabel, eval.fullSensorLabel)

        val size = activeRawSize()
        if (size == null) {
            onUnsupported()
            return
        }

        createRawReader(size)
    }

    private fun activeRawSize(): Size? =
        if (currentMode == RawSensorMode.FULL_SENSOR && isFullSensorSupported) {
            maxRawSize ?: defaultRawSize
        } else {
            defaultRawSize
        }

    private fun createRawReader(size: Size) {
        rawReader?.close()
        rawReader =
            ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2).apply {
                setOnImageAvailableListener({ reader ->
                    pendingImage = reader.acquireNextImage()
                    tryWriteDng()
                }, backgroundHandler)
            }
    }

    override fun onCameraOpened(
        camera: CameraDevice,
        surface: Surface,
    ) {
        currentPreviewSurface = surface
        val targets = mutableListOf(surface)
        rawReader?.surface?.let { targets.add(it) }

        previewRequestBuilder =
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }

        createCaptureSession(camera, targets) { startRepeatingRequest() }
    }

    fun setSensorMode(mode: RawSensorMode) {
        if (currentMode == mode) return
        currentMode = mode
        backgroundHandler.post {
            val camera = cameraDevice ?: return@post
            val surface = currentPreviewSurface ?: return@post
            val size = activeRawSize() ?: return@post

            createRawReader(size)

            val targets = mutableListOf(surface)
            rawReader?.surface?.let { targets.add(it) }

            createCaptureSession(camera, targets) { startRepeatingRequest() }
        }
    }

    private fun startRepeatingRequest() {
        try {
            val builder = previewRequestBuilder ?: return
            val session = captureSession ?: return
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to start repeating request", e)
        }
    }

    /** Issues a still-capture request to the RAW reader and resumes preview afterwards. */
    fun captureRaw() {
        val device = cameraDevice ?: return
        val reader = rawReader ?: return
        val session = captureSession ?: return

        try {
            val captureBuilder =
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    if (currentMode == RawSensorMode.FULL_SENSOR && isFullSensorSupported) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            set(
                                CaptureRequest.SENSOR_PIXEL_MODE,
                                CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION,
                            )
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        set(
                            CaptureRequest.SENSOR_PIXEL_MODE,
                            CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT,
                        )
                    }
                }
            session.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        pendingResult = result
                        tryWriteDng()
                    }
                },
                backgroundHandler,
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to capture RAW", e)
        }
    }

    private fun evaluateSensorCapabilities(characteristics: CameraCharacteristics): SensorCapabilities {
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        var fullSupported =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                caps?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR,
                ) == true
            } else {
                false
            }

        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val defaultRawSize =
            streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        val pixelBinLabel = defaultRawSize?.let { formatMegapixels(it.width, it.height) } ?: ""

        var maxRawSize =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val maxResStreamMap =
                    characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION,
                    )
                maxResStreamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull {
                    it.width.toLong() * it.height
                }
            } else {
                null
            }

        if (maxRawSize != null) {
            fullSupported = true
        }

        if (!fullSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val physicalIds = characteristics.physicalCameraIds
            for (physicalId in physicalIds) {
                try {
                    val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                    val physicalCaps =
                        physicalChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    val physicalHasUltra =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            physicalCaps?.contains(
                                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR,
                            ) == true
                        } else {
                            false
                        }

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
                            it.width.toLong() * it.height
                        }

                    if (physicalHasUltra || physicalMaxSize != null) {
                        fullSupported = true
                        if (maxRawSize == null ||
                            (
                                physicalMaxSize != null &&
                                    physicalMaxSize.width.toLong() * physicalMaxSize.height > maxRawSize.width.toLong() * maxRawSize.height
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
            defaultRawSize = defaultRawSize,
            maxRawSize = maxRawSize,
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

    /** Runs on the background handler; writes the DNG once both the image and its result exist. */
    private fun tryWriteDng() {
        val image = pendingImage ?: return
        val result = pendingResult ?: return
        val characteristics = currentCharacteristics ?: return
        pendingImage = null
        pendingResult = null

        try {
            val fileName = "RAW_${SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())}.dng"
            val uri = saveDng(characteristics, result, image, fileName)
            if (uri != null) onDngSaved(uri, sensorOrientation)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write DNG", e)
        } finally {
            image.close()
        }
    }

    private fun saveDng(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        image: Image,
        fileName: String,
    ): Uri? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CameraSamples")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val uri =
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    DngCreator(characteristics, result).use { dng ->
                        dng.setOrientation(exifOrientation(sensorOrientation))
                        dng.writeImage(out, image)
                    }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                Log.e(TAG, "DNG write (MediaStore) failed", e)
                resolver.delete(uri, null, null)
                null
            }
        } else {
            @Suppress("DEPRECATION")
            val dir =
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "CameraSamples",
                )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            try {
                FileOutputStream(file).use { out ->
                    DngCreator(characteristics, result).use { dng ->
                        dng.setOrientation(exifOrientation(sensorOrientation))
                        dng.writeImage(out, image)
                    }
                }
                MediaStoreSaver.scanFile(context, file, "image/x-adobe-dng")
                Uri.fromFile(file)
            } catch (e: Exception) {
                Log.e(TAG, "DNG write (file) failed", e)
                null
            }
        }
    }

    private fun exifOrientation(degrees: Int): Int =
        when (degrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }

    override fun onCameraClosed() {
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
        rawReader?.close()
        rawReader = null
        currentPreviewSurface = null
    }
}

private data class SensorCapabilities(
    val isFullSensorSupported: Boolean,
    val defaultRawSize: Size?,
    val maxRawSize: Size?,
    val pixelBinLabel: String,
    val fullSensorLabel: String,
)
