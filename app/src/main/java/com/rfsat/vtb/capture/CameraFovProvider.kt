package com.rfsat.vtb.capture

import android.hardware.camera2.CameraCharacteristics
import android.util.SizeF
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import com.rfsat.vtb.log.Logger
import kotlin.math.atan

/**
 * Derives the RECORDED FRAME's horizontal field of view from the bound
 * camera's actual optics instead of a hardcoded per-model table:
 *
 *     FOV = 2 * atan( effectiveSensorWidth / (2 * f * zoom) )
 *
 * using SENSOR_INFO_PHYSICAL_SIZE and LENS_INFO_AVAILABLE_FOCAL_LENGTHS
 * from Camera2, and the live CameraX zoom ratio. Two corrections matter:
 *
 *  1. ASPECT CROP — 16:9 video is centre-cropped from the (usually 4:3)
 *     sensor, so the sensor dimensions are first cropped to the output
 *     aspect before the angle is computed.
 *  2. ORIENTATION — VTB records portrait, so the FRAME's horizontal axis
 *     is the sensor's SHORT side. (This is why the computed value ~46 deg
 *     on a Galaxy S24 main camera is well below the ~75 deg often quoted:
 *     that figure is the landscape/diagonal FOV.)
 *
 * Works on any device and any lens the camera stack binds (main, tele,
 * ultrawide) at any zoom — a static "S24 = X deg" lookup would silently
 * break on all of those. Returns null if the device withholds the
 * characteristics (rare); the manual FOV field then keeps its value.
 *
 * NOTE: applies to clips recorded IN the app. An imported video carries no
 * reliable optics metadata; for those the field stays a manual entry (the
 * auto value is still a good prefill if the clip came from this phone at
 * the same zoom).
 */
object CameraFovProvider {

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun horizontalFovDeg(camera: Camera, videoAspectLandscape: Double = 16.0 / 9.0): Double? {
        return try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val sensor: SizeF = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val focals = info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return null
            val f = focals.firstOrNull()?.toDouble() ?: return null
            if (f <= 0.0) return null
            val zoom = (camera.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f)
                .toDouble().coerceAtLeast(0.01)

            var longMm = sensor.width.toDouble()   // sensor is landscape: width = long side
            var shortMm = sensor.height.toDouble()
            // centre-crop the sensor to the video aspect (in landscape terms)
            val sensorAspect = longMm / shortMm
            if (sensorAspect < videoAspectLandscape) {
                shortMm = longMm / videoAspectLandscape // typical: 4:3 sensor -> crop short side
            } else if (sensorAspect > videoAspectLandscape) {
                longMm = shortMm * videoAspectLandscape
            }
            // portrait recording: frame width axis == sensor short side
            val fov = Math.toDegrees(2.0 * atan(shortMm / (2.0 * f * zoom)))
            Logger.i("CameraFovProvider",
                "sensor=${"%.2f".format(sensor.width)}x${"%.2f".format(sensor.height)}mm " +
                "f=${"%.2f".format(f)}mm zoom=${"%.2f".format(zoom)}x -> hFOV=${"%.1f".format(fov)} deg")
            fov
        } catch (t: Throwable) {
            Logger.e("CameraFovProvider", "FOV query failed — keeping manual value", t)
            null
        }
    }
}
