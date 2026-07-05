package com.rfsat.vtb.capture

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.rfsat.vtb.ui.BaseActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rfsat.vtb.ballistics.Atmosphere
import com.rfsat.vtb.ballistics.BallisticsEngine
import com.rfsat.vtb.databinding.ActivityCaptureBinding
import com.rfsat.vtb.log.Logger
import com.rfsat.vtb.profiles.ProfileRepository
import com.rfsat.vtb.profiles.RifleProfile
import com.rfsat.vtb.results.AdjustmentCalculator
import com.rfsat.vtb.results.AnalysisSession
import com.rfsat.vtb.results.ResultsActivity
import com.rfsat.vtb.wind.WindEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureActivity : BaseActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private lateinit var repo: ProfileRepository
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var pendingUri: Uri? = null // set by the recorder, the video-import picker, or an auto-trigger

    private var rifle: RifleProfile = RifleProfile.DEFAULT
    private val nudgeStep = 0.005 // normalized frame fraction per tap

    private var audioPermissionGranted = false
    private var shotDetector: ShotDetector? = null
    private var isArmed = false
    /** Reference (background) frame grabbed from the live preview at arm-time —
     *  needed because an auto-triggered recording starts right at the shot,
     *  leaving no pre-shot footage in the clip itself to pull one from. */
    private var pendingReferenceBitmap: Bitmap? = null
    private var autoStopJob: kotlinx.coroutines.Job? = null

    companion object {
        /** Seconds of post-settle trail drift to record/track for the wind
         *  estimate. More = better fit statistics, at the cost of decode time
         *  (frames are sampled every 33 ms). */
        private const val DRIFT_OBSERVATION_S = 2.5

        private const val TAG = "CaptureActivity"
        // Rough budget for mic-buffer read latency + detection loop polling +
        // CameraX recorder startup — the trail's first fraction of a second
        // may already be gone by the time frames actually start landing.
        // See ShotDetector's doc comment for the full caveat.
        private const val AUTO_TRIGGER_LATENCY_S = 0.12
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.CAMERA] == true) startCamera() else {
            Toast.makeText(this, "Camera permission is required to capture the trail.", Toast.LENGTH_LONG).show()
            finish()
        }
        audioPermissionGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (!audioPermissionGranted) {
            Logger.w(TAG, "RECORD_AUDIO not granted — Arm (Auto-Trigger) will be unavailable")
        }
    }

    private val importVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pendingUri = uri
            pendingReferenceBitmap = null // imported clips use TrailExtractor's internal reference-frame lookup
            binding.btnAnalyze.isEnabled = true
            Logger.i(TAG, "Imported video: $uri")
            Toast.makeText(this, "Video imported — ready to analyze.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ProfileRepository(this)
        rifle = repo.getRifle()
        setupBottomNav(com.rfsat.vtb.R.id.nav_capture)

        binding.crosshair.offsetXNorm = rifle.boresightOffsetXNorm
        binding.crosshair.offsetYNorm = rifle.boresightOffsetYNorm

        binding.etTargetDistance.hint =
            "Target distance (${com.rfsat.vtb.ui.UnitsManager.distanceUnitLabel()})"

        binding.btnNudgeLeft.setOnClickListener { nudgeBoresight(-nudgeStep, 0.0) }
        binding.btnNudgeRight.setOnClickListener { nudgeBoresight(nudgeStep, 0.0) }
        binding.btnNudgeUp.setOnClickListener { nudgeBoresight(0.0, -nudgeStep) }
        binding.btnNudgeDown.setOnClickListener { nudgeBoresight(0.0, nudgeStep) }
        binding.btnNudgeReset.setOnClickListener { setBoresight(0.0, 0.0) }

        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnImportVideo.setOnClickListener { importVideoLauncher.launch("video/*") }
        binding.btnAnalyze.setOnClickListener { runAnalysis() }
        binding.btnAnalyze.isEnabled = false

        binding.btnArm.setOnClickListener { toggleArm() }
        binding.etSensitivity.setText("70")

        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        audioPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) startCamera()
        val toRequest = mutableListOf<String>()
        if (!cameraGranted) toRequest.add(Manifest.permission.CAMERA)
        if (!audioPermissionGranted) toRequest.add(Manifest.permission.RECORD_AUDIO)
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }

    override fun onDestroy() {
        shotDetector?.stop()
        autoStopJob?.cancel()
        super.onDestroy()
    }

    private fun nudgeBoresight(dx: Double, dy: Double) =
        setBoresight(binding.crosshair.offsetXNorm + dx, binding.crosshair.offsetYNorm + dy)

    private fun setBoresight(x: Double, y: Double) {
        val clampedX = x.coerceIn(-0.45, 0.45)
        val clampedY = y.coerceIn(-0.45, 0.45)
        binding.crosshair.offsetXNorm = clampedX
        binding.crosshair.offsetYNorm = clampedY
        rifle = rifle.copy(boresightOffsetXNorm = clampedX, boresightOffsetYNorm = clampedY)
        repo.saveRifle(rifle)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val recorder = Recorder.Builder().build()
            val capture = VideoCapture.withOutput(recorder)
            videoCapture = capture

            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            // Auto-FOV: fill the field from the real optics now and whenever
            // the zoom changes (a zoom change invalidates any manual value).
            // The field stays editable for imported clips from other devices.
            camera.cameraInfo.zoomState.observe(this) {
                CameraFovProvider.horizontalFovDeg(camera)?.let { fov ->
                    binding.etHorizontalFov.setText(String.format("%.1f", fov))
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- Manual record/stop (unchanged flow) ----

    private fun toggleRecording() {
        if (isArmed) disarm() // manual record overrides an armed auto-trigger
        val capture = videoCapture ?: return
        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            binding.btnRecord.text = getString(com.rfsat.vtb.R.string.record)
            binding.btnAnalyze.isEnabled = true
            return
        }
        startRecordingInternal { binding.btnRecord.text = getString(com.rfsat.vtb.R.string.stop) }
    }

    private fun startRecordingInternal(onStarted: () -> Unit) {
        val capture = videoCapture ?: return
        val name = "vapor_trail_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        val outputOptions = androidx.camera.video.MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recording = capture.output.prepareRecording(this, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    pendingUri = event.outputResults.outputUri
                    Logger.i(TAG, "Recording finalized: ${pendingUri}")
                }
            }
        onStarted()
    }

    // ---- Arm / auto-trigger-on-shot ----

    private fun toggleArm() {
        if (isArmed) { disarm(); return }
        if (!audioPermissionGranted) {
            Toast.makeText(this, "Microphone permission is required for auto-trigger.", Toast.LENGTH_LONG).show()
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (videoCapture == null) {
            Toast.makeText(this, "Camera isn't ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val previewBitmap = binding.previewView.bitmap
        if (previewBitmap == null) {
            Toast.makeText(this, "Preview isn't ready yet — try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingReferenceBitmap = previewBitmap
        isArmed = true
        binding.btnArm.text = "Disarm"
        binding.tvArmStatus.text = "Listening for shot…"
        binding.btnRecord.isEnabled = false

        val sensitivity = binding.etSensitivity.text.toString().toIntOrNull() ?: 70
        shotDetector = ShotDetector { onShotDetected() }.also { it.start(sensitivity) }
        Logger.i(TAG, "Armed — listening for shot (sensitivity=$sensitivity)")
    }

    private fun disarm() {
        shotDetector?.stop()
        shotDetector = null
        isArmed = false
        binding.btnArm.text = "Arm (Auto-Trigger)"
        binding.tvArmStatus.text = ""
        binding.btnRecord.isEnabled = true
    }

    private fun onShotDetected() {
        if (!isArmed) return // already handled / disarmed concurrently
        shotDetector?.stop()
        shotDetector = null
        binding.tvArmStatus.text = "Shot detected — recording…"
        Logger.i(TAG, "Shot detected — starting recording")

        // Pre-fill shot-break offset with the assumed detection-to-frames latency,
        // since the clip itself starts essentially at the shot.
        binding.etShotBreakSeconds.setText(AUTO_TRIGGER_LATENCY_S.toString())

        startRecordingInternal {
            binding.btnRecord.text = getString(com.rfsat.vtb.R.string.stop)
            binding.btnArm.text = "Arm (Auto-Trigger)"

            // Duration = expected time-of-flight to the target, plus margin
            // for wind-lengthened flight and trailing smoke dispersal.
            val bullet = repo.getBullet()
            val zeroRifle = repo.getRifle()
            val zeroScope = repo.getScope()
            val targetDistanceM = readTargetDistanceMeters()
            val tof = BallisticsEngine.zeroedTimeOfFlight(
                bullet, Atmosphere(), targetDistanceM,
                zeroRifle.zeroDistanceM,
                com.rfsat.vtb.results.AdjustmentCalculator.effectiveSightHeightM(zeroRifle, zeroScope)
            )
            val durationS = (tof * 1.15 + AUTO_TRIGGER_LATENCY_S).coerceAtLeast(tof + 0.3)
            Logger.i(TAG, "Auto-stop scheduled: est. time-of-flight=${tof}s -> recording for ${durationS}s")
            binding.tvArmStatus.text = "Recording (~${"%.1f".format(durationS)}s)…"

            autoStopJob = lifecycleScope.launch {
                delay((durationS * 1000).toLong())
                val active = recording
                if (active != null) {
                    active.stop()
                    recording = null
                    binding.btnRecord.text = getString(com.rfsat.vtb.R.string.record)
                    binding.btnAnalyze.isEnabled = true
                    binding.tvArmStatus.text = "Recording complete — ready to analyze."
                    Logger.i(TAG, "Auto-stop fired")
                }
                isArmed = false
                binding.btnRecord.isEnabled = true
            }
        }
    }

    /**
     * The full pipeline (video decode + physics simulation) is CPU/IO heavy
     * — running it on the UI thread was the cause of the reported crash
     * (an ANR under the hood). Everything below runs on Dispatchers.Default;
     * only the final activity launch hops back to the main thread.
     */
    private fun runAnalysis() {
        val uri = pendingUri
        if (uri == null) {
            Toast.makeText(this, "No video available yet — record or import one first.", Toast.LENGTH_SHORT).show()
            return
        }
        val shotBreakOffsetS = binding.etShotBreakSeconds.text.toString().toDoubleOrNull() ?: 0.5
        val targetDistanceYd = readTargetDistanceMeters() / 0.9144
        val fovDeg = binding.etHorizontalFov.text.toString().toDoubleOrNull() ?: 60.0
        val referenceBitmap = pendingReferenceBitmap

        setUiBusy(true)

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                Logger.i(TAG, "Analysis starting: uri=$uri shotBreak=${shotBreakOffsetS}s target=${targetDistanceYd}yd fov=${fovDeg}deg externalRef=${referenceBitmap != null}")

                val bullet = repo.getBullet()
                val activeRifle = repo.getRifle()
                val scope = repo.getScope()
                val atmosphere = Atmosphere() // TODO: wire up a range-conditions input screen

                // Copy the source (recorded or imported) into app cache and
                // analyze from a plain file path — content:// Uris can crash
                // MediaMetadataRetriever at the native layer on some devices.
                val localFile = copyUriToCache(uri)
                if (localFile == null) {
                    withContext(Dispatchers.Main) {
                        setUiBusy(false)
                        Toast.makeText(this@CaptureActivity, "Could not read the video file — see Log.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                Logger.i(TAG, "Video copied to cache: ${localFile.absolutePath} (${localFile.length() / 1024} KB)")

                // TOF along the ZEROED trajectory, computed up-front: it sizes
                // the tracking window AND the estimator's settle threshold, so
                // both stay consistent with the zero calibration the solver uses.
                val targetDistanceM = targetDistanceYd * 0.9144
                val tofS = BallisticsEngine.zeroedTimeOfFlight(
                    bullet, atmosphere, targetDistanceM,
                    activeRifle.zeroDistanceM,
                    AdjustmentCalculator.effectiveSightHeightM(activeRifle, scope)
                )
                val settleS = tofS * 1.2
                // Track for settle + DRIFT_OBSERVATION_S of usable drift. The
                // old fixed 2.0 s window starved the estimator at long range:
                // at 300 m settle alone is ~2 s, leaving zero usable frames.
                val extraction = TrailExtractor.extract(
                    localFile.absolutePath, shotBreakOffsetS,
                    clipDurationAfterShotS = settleS + DRIFT_OBSERVATION_S,
                    externalReferenceBitmap = referenceBitmap
                )
                localFile.delete()
                val observations = extraction.observations
                if (observations.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        setUiBusy(false)
                        Toast.makeText(this@CaptureActivity, "No trail detected — check lighting/contrast and try again.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Calibration is done in the extractor's own decoded-frame pixel
                // space (returned in ExtractionResult), so pixel coordinates and
                // the boresight reference are guaranteed consistent — including
                // for imported clips whose resolution differs from the preview.
                val frameWidthPx = extraction.decodedFrameWidth
                val frameHeightPx = extraction.decodedFrameHeight
                val boresightXNorm = 0.5 + activeRifle.boresightOffsetXNorm
                val boresightYNorm = 0.5 + activeRifle.boresightOffsetYNorm

                val calibration = TrailCalibration(
                    horizontalFovDeg = fovDeg,
                    frameWidthPx = frameWidthPx,
                    frameHeightPx = frameHeightPx,
                    boresightPixelX = boresightXNorm * frameWidthPx,
                    boresightPixelY = boresightYNorm * frameHeightPx
                )

                val windSamples = WindEstimator.estimate(
                    calibration, observations, targetDistanceM, settleTimeS = settleS
                )
                Logger.i(TAG, "Estimated ${windSamples.size} wind samples " +
                    "(tof=${"%.2f".format(tofS)}s settle=${"%.2f".format(settleS)}s)")

                val adjustment = AdjustmentCalculator.computeAdjustment(
                    bullet, activeRifle, scope, atmosphere, targetDistanceYd, windSamples
                )
                Logger.i(TAG, "Adjustment: windage=${adjustment.windageDirection} ${adjustment.windageScopeUnits} ${adjustment.scopeUnitLabel}, " +
                    "elevation=${adjustment.elevationDirection} ${adjustment.elevationScopeUnits} ${adjustment.scopeUnitLabel}, " +
                    "wind=${adjustment.estimatedCrosswindMps} m/s (conf=${adjustment.windConfidence}), warnings=${adjustment.warnings.size}")

                AnalysisSession.windSamples = windSamples
                AnalysisSession.adjustment = adjustment
                AnalysisSession.targetDistanceYd = targetDistanceYd
                AnalysisSession.persist(this@CaptureActivity)

                withContext(Dispatchers.Main) {
                    setUiBusy(false)
                    startActivity(Intent(this@CaptureActivity, ResultsActivity::class.java))
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "Analysis failed", t)
                withContext(Dispatchers.Main) {
                    setUiBusy(false)
                    Toast.makeText(this@CaptureActivity, "Analysis failed: ${t.message}. See the Log tab for details.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Interprets the target-distance field in the active unit system -> metres. */
    private fun readTargetDistanceMeters(): Double {
        val typed = binding.etTargetDistance.text.toString().toDoubleOrNull()
            ?: if (com.rfsat.vtb.ui.UnitsManager.isImperial()) 100.0 else 100.0
        return com.rfsat.vtb.ui.UnitsManager.inputDistanceToMeters(typed)
    }

    /** Streams a content Uri into a private cache file. Returns null on failure. */
    private fun copyUriToCache(uri: Uri): java.io.File? {
        return try {
            val out = java.io.File(cacheDir, "analysis_input.mp4")
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) { Logger.e(TAG, "openInputStream returned null for $uri"); return null }
                out.outputStream().use { output -> input.copyTo(output) }
            }
            if (out.length() == 0L) { Logger.e(TAG, "Copied video is 0 bytes: $uri"); null } else out
        } catch (t: Throwable) {
            Logger.e(TAG, "Failed to copy video to cache", t)
            null
        }
    }

    private fun setUiBusy(busy: Boolean) {
        binding.btnAnalyze.isEnabled = !busy && pendingUri != null
        binding.btnRecord.isEnabled = !busy
        binding.btnImportVideo.isEnabled = !busy
        binding.btnArm.isEnabled = !busy
        binding.progressAnalyzing.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
    }
}
