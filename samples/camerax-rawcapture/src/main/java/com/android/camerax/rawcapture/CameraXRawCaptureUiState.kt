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

import android.net.Uri

enum class RawSensorMode {
    PIXEL_BIN,
    FULL_SENSOR,
}

sealed interface CameraXRawCaptureUiState {
    data object Initial : CameraXRawCaptureUiState

    data class Previewing(
        val selectedMode: RawSensorMode = RawSensorMode.PIXEL_BIN,
        val isFullSensorSupported: Boolean = false,
        val pixelBinResolutionLabel: String = "",
        val fullSensorResolutionLabel: String = "",
        val showSettings: Boolean = false,
    ) : CameraXRawCaptureUiState

    /**
     * A single shutter press produced two files: a RAW [dngUri] (`image/x-adobe-dng`) and a
     * companion [jpegUri] (`image/jpeg`). The JPEG is decoded for the on-screen review; the DNG holds
     * the untouched sensor data.
     */
    data class Captured(
        val dngUri: Uri,
        val jpegUri: Uri,
        val mode: RawSensorMode = RawSensorMode.PIXEL_BIN,
    ) : CameraXRawCaptureUiState

    /** The camera does not advertise RAW + JPEG simultaneous output (e.g. most emulators). */
    data object Unsupported : CameraXRawCaptureUiState

    data class Error(
        val errorMessage: String?,
    ) : CameraXRawCaptureUiState
}
