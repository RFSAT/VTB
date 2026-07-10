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
    private var lastAutoFov: String? = null
    private var recording: Recording? = null
    private var pendingUri: Uri? = null // set by the recorder, the video-import picker, or an auto-trigger

    private var rifle: RifleProfile = RifleProfile.DEFAULT
    private val nudgeStep = 0.005 // normalized frame fraction per tap
    private var lastAutoZoom: String? = null

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

        // Capture-screen field persistence (v13.0). The bottom nav keeps the
        // back stack flat by finish()ing the source activity on every tab
        // hop, so Capture is REBUILT each time the user returns from Results
        // — without these prefs the target distance (etc.) was lost right
        // after every analysis.
        private const val FIELD_PREFS = "vtb_capture_fields"
        private const val KEY_DISTANCE_M = "target_distance_m" // stored SI so a unit-system switch stays correct
        private const val KEY_FOV = "fov_text"
        private const val KEY_ZOOM = "zoom_text"
        private const val KEY_SENSITIVITY = "sensitivity_text"
        private const val REQ_BT_CONNECT = 41
        // Rough budget for mic-buffer read latency + detection loop polling +
        // CameraX recorder startup — the trail's first fraction of a second
        // may already be gone by the time frames actually start landing.
        // See ShotDetector's doc comment for the full caveat.
        private const val AUTO_TRIGGER_LATENCY_S = 0.12
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.CAMERA] == true) startCamera() else {
            notifyUser("Camera permission is required to capture the trail.")
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
            notifyUser("Video imported — ready to analyze.")
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

        binding.tvTargetLabel.text =
            "Target (${com.rfsat.vtb.ui.UnitsManager.distanceUnitLabel()})"

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
        restoreCaptureFields()

        // v17.0: range conditions. Phone sensors auto-refresh on entry
        // (barometer on the S24; ambient temp/humidity where the device has
        // them); a paired Kestrel can override on demand.
        binding.tvEnvStatus.text = com.rfsat.vtb.environment.EnvironmentManager.describe()
        com.rfsat.vtb.environment.EnvironmentManager.refreshFromPhoneSensors(this) {
            binding.tvEnvStatus.text = com.rfsat.vtb.environment.EnvironmentManager.describe()
        }
        binding.btnKestrel.setOnClickListener { readKestrel() }

        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        audioPermissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted) startCamera()
        val toRequest = mutableListOf<String>()
        if (!cameraGranted) toRequest.add(Manifest.permission.CAMERA)
        if (!audioPermissionGranted) toRequest.add(Manifest.permission.RECORD_AUDIO)
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }

    override fun onPause() {
        saveCaptureFields()
        super.onPause()
    }

    override fun onDestroy() {
        shotDetector?.stop()
        autoStopJob?.cancel()
        super.onDestroy()
    }

    // ---- Range conditions (v17.0) ----

    private fun readKestrel() {
        // v18.0: BLE scan permissions. 12+: BLUETOOTH_SCAN (neverForLocation)
        // + BLUETOOTH_CONNECT; pre-12: BLE scanning requires fine location.
        val needed = if (android.os.Build.VERSION.SDK_INT >= 31) arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) else arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        val missing = needed.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_BT_CONNECT)
            return
        }
        // Bonded first (5700 Elite pairs classically); the DROP D3 never
        // appears in paired devices — it's advertising-only — so fall
        // through to a short BLE scan.
        val bonded = com.rfsat.vtb.environment.KestrelProvider.findPairedKestrel()
        if (bonded != null) {
            startKestrelRead(bonded)
            return
        }
        binding.btnKestrel.isEnabled = false
        binding.tvEnvStatus.text = "Scanning for Kestrel…"
        com.rfsat.vtb.environment.KestrelProvider.scanForKestrel(this) { device ->
            if (device == null) {
                binding.btnKestrel.isEnabled = true
                binding.tvEnvStatus.text = com.rfsat.vtb.environment.EnvironmentManager.describe()
                notifyUser("No Kestrel found nearby — make sure it's on and close by. Advertisers seen were logged (Log tab).")
            } else {
                startKestrelRead(device)
            }
        }
    }

    private fun startKestrelRead(device: android.bluetooth.BluetoothDevice) {
        binding.btnKestrel.isEnabled = false
        binding.tvEnvStatus.text = "Reading Kestrel…"
        com.rfsat.vtb.environment.KestrelProvider.read(this, device) { got ->
            binding.btnKestrel.isEnabled = true
            binding.tvEnvStatus.text = com.rfsat.vtb.environment.EnvironmentManager.describe()
            if (!got) notifyUser("Kestrel connected but no readable environment values — its GATT layout was logged (Log tab) for exact wiring.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_CONNECT &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) readKestrel()
    }

    // ---- Capture-field persistence ----

    private fun saveCaptureFields() {
        val e = getSharedPreferences(FIELD_PREFS, MODE_PRIVATE).edit()
        // Distance is stored in metres and re-rendered in the active unit on
        // restore, so it survives an Imperial<->Metric switch unchanged.
        binding.etTargetDistance.text.toString().toDoubleOrNull()?.let {
            e.putFloat(KEY_DISTANCE_M, com.rfsat.vtb.ui.UnitsManager.inputDistanceToMeters(it).toFloat())
        }
        e.putString(KEY_FOV, binding.etHorizontalFov.text.toString())
        e.putString(KEY_ZOOM, binding.etZoom.text.toString())
        e.putString(KEY_SENSITIVITY, binding.etSensitivity.text.toString())
        e.apply()
    }

    private fun restoreCaptureFields() {
        val p = getSharedPreferences(FIELD_PREFS, MODE_PRIVATE)
        val distM = p.getFloat(KEY_DISTANCE_M, -1f)
        if (distM > 0f) {
            val display = com.rfsat.vtb.ui.UnitsManager.displayDistance(distM.toDouble())
            binding.etTargetDistance.setText(String.format("%.0f", display))
        }
        binding.etSensitivity.setText(p.getString(KEY_SENSITIVITY, "70"))
        // FOV/zoom: restore as prefills, but ALSO seed lastAuto* with them so
        // the live-camera observer still treats them as replaceable auto
        // values — live capture keeps tracking real optics/zoom, while a
        // value typed AFTER the camera bound (imported-clip workflow) still
        // survives, exactly as the v12.1 stomping guard intended.
        p.getString(KEY_FOV, null)?.takeIf { it.isNotBlank() }?.let {
            binding.etHorizontalFov.setText(it); lastAutoFov = it
        }
        p.getString(KEY_ZOOM, null)?.takeIf { it.isNotBlank() }?.let {
            binding.etZoom.setText(it); lastAutoZoom = it
        }
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
            camera.cameraInfo.zoomState.observe(this) { zoomState ->
                // v13.0: FOV field now holds the BASE (1x) FOV; the zoom
                // lives in its own field. Effective FOV is recombined at
                // analysis time — this keeps both numbers meaningful for
                // imported clips, where the user knows "46 deg lens, shot
                // at 3x" but not the folded-together effective angle.
                CameraFovProvider.horizontalFovDeg(camera, zoomOverride = 1.0)?.let { fov ->
                    val txt = String.format("%.1f", fov)
                    val cur = binding.etHorizontalFov.text.toString()
                    // Only replace an EMPTY field or our own previous auto
                    // value. zoomState re-emits on every camera re-bind (each
                    // return from the picker), and the unguarded version kept
                    // stomping manual entries for imported clips.
                    if (cur.isBlank() || cur == lastAutoFov) {
                        binding.etHorizontalFov.setText(txt)
                    }
                    lastAutoFov = txt
                }
                val zoomTxt = String.format("%.1f", (zoomState?.zoomRatio ?: 1.0f).toDouble())
                val curZoom = binding.etZoom.text.toString()
                if (curZoom.isBlank() || curZoom == lastAutoZoom) {
                    binding.etZoom.setText(zoomTxt)
                }
                lastAutoZoom = zoomTxt
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
            notifyUser("Microphone permission is required for auto-trigger.")
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        if (videoCapture == null) {
            notifyUser("Camera isn't ready yet.")
            return
        }
        val previewBitmap = binding.previewView.bitmap
        if (previewBitmap == null) {
            notifyUser("Preview isn't ready yet — try again in a moment.")
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
            notifyUser("No video available yet — record or import one first.")
            return
        }
        val shotBreakOffsetS = binding.etShotBreakSeconds.text.toString().toDoubleOrNull() ?: 0.5
        val targetDistanceYd = readTargetDistanceMeters() / 0.9144
        val fovDeg = binding.etHorizontalFov.text.toString().toDoubleOrNull() ?: 60.0
        // FOV gates the entire pixel-to-angle calibration; out-of-range values
        // (e.g. a distance typed into the wrong field) poison every result.
        if (fovDeg !in 10.0..120.0) {
            Logger.e(TAG, "Rejected analysis: FOV $fovDeg deg outside 10-120")
            notifyUser("Camera FOV must be 10–120° (got ${"%.0f".format(fovDeg)}°).")
            return
        }
        // Zoom the clip was RECORDED at. Auto-filled from the live camera;
        // for imported videos (no optics metadata in the file) the user
        // enters it manually — this was previously impossible and forced
        // folding zoom into a hand-computed FOV. 0.5x covers ultrawide.
        val zoom = binding.etZoom.text.toString().toDoubleOrNull() ?: 1.0
        if (zoom !in 0.5..50.0) {
            Logger.e(TAG, "Rejected analysis: zoom ${zoom}x outside 0.5-50")
            notifyUser("Zoom must be 0.5–50× (got ${"%.1f".format(zoom)}×).")
            return
        }
        // Effective FOV: the angular width actually recorded. Correct optics,
        // not linear division — 2*atan(tan(base/2)/zoom) — though the two
        // agree within ~2% for narrow angles.
        val effectiveFovDeg = Math.toDegrees(
            2.0 * kotlin.math.atan(kotlin.math.tan(Math.toRadians(fovDeg / 2.0)) / zoom)
        )
        val referenceBitmap = pendingReferenceBitmap

        setUiBusy(true)

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                Logger.i(TAG, "Analysis starting: uri=$uri shotBreak=${shotBreakOffsetS}s target=${targetDistanceYd}yd " +
                    "baseFov=${"%.1f".format(fovDeg)}deg zoom=${"%.1f".format(zoom)}x -> effFov=${"%.2f".format(effectiveFovDeg)}deg " +
                    "externalRef=${referenceBitmap != null}")

                val bullet = repo.getBullet()
                val activeRifle = repo.getRifle()
                val scope = repo.getScope()
                val atmosphere = com.rfsat.vtb.environment.EnvironmentManager.current.atmosphere
                Logger.i(TAG, "Analysis environment: ${com.rfsat.vtb.environment.EnvironmentManager.describe()}")

                // Copy the source (recorded or imported) into app cache and
                // analyze from a plain file path — content:// Uris can crash
                // MediaMetadataRetriever at the native layer on some devices.
                val localFile = copyUriToCache(uri)
                if (localFile == null) {
                    withContext(Dispatchers.Main) {
                        setUiBusy(false)
                        notifyUser("Could not read the video file — see Log.")
                    }
                    return@launch
                }
                Logger.i(TAG, "Video copied to cache: ${localFile.absolutePath} (${localFile.length() / 1024} KB)")

                // TOF along the ZEROED trajectory, computed up-front: it sizes
                // the tracking window AND the estimator's settle threshold, so
                // both stay consistent with the zero calibration the solver uses.
                val targetDistanceM = targetDistanceYd * 0.9144
                val sightHeightM = AdjustmentCalculator.effectiveSightHeightM(activeRifle, scope)
                val tofS = BallisticsEngine.zeroedTimeOfFlight(
                    bullet, atmosphere, targetDistanceM,
                    activeRifle.zeroDistanceM,
                    sightHeightM
                )
                val settleS = tofS * 1.2
                val tracer = bullet.isTracer
                // Tracking window differs fundamentally by mode:
                //  VAPOR — the wind signal is the trail's drift AFTER the
                //    bullet has passed, so track settle + DRIFT_OBSERVATION_S.
                //    (The old fixed 2.0 s window starved the estimator at long
                //    range: at 300 m settle alone is ~2 s.)
                //  TRACER — the wind signal is the bullet's own deflection
                //    DURING flight; past impact the bright point is just the
                //    strike flash, so track only tof + a small margin.
                val trackWindowS = if (tracer) tofS + 0.3 else settleS + DRIFT_OBSERVATION_S
                if (tracer) Logger.i(TAG, "TRACER mode: tracking bullet for ${"%.2f".format(trackWindowS)}s (tof=${"%.2f".format(tofS)}s)")
                val extraction = TrailExtractor.extract(
                    localFile.absolutePath, shotBreakOffsetS,
                    clipDurationAfterShotS = trackWindowS,
                    externalReferenceBitmap = referenceBitmap,
                    mode = if (tracer) TrailExtractor.Mode.TRACER else TrailExtractor.Mode.VAPOR
                )
                localFile.delete()
                val observations = extraction.observations
                if (observations.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        setUiBusy(false)
                        notifyUser("No trail detected — check lighting/contrast and try again.")
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
                    horizontalFovDeg = effectiveFovDeg,
                    frameWidthPx = frameWidthPx,
                    frameHeightPx = frameHeightPx,
                    boresightPixelX = boresightXNorm * frameWidthPx,
                    boresightPixelY = boresightYNorm * frameHeightPx
                )

                val rawSamples = if (tracer) {
                    com.rfsat.vtb.wind.TracerWindEstimator.estimate(
                        calibration, observations, bullet, atmosphere,
                        zeroDistanceM = activeRifle.zeroDistanceM,
                        sightHeightM = sightHeightM,
                        targetDistanceM = targetDistanceM
                    )
                } else {
                    WindEstimator.estimate(
                        calibration, observations, targetDistanceM, settleTimeS = settleS
                    )
                }
                // v18.0 (user request): chart index = distance covered by the
                // bullet at the sample's time, WITH drag decay — 1D speed
                // decay integral, un-terminated, so it never saturates.
                // Chart-only: wind magnitudes are untouched.
                val windSamples = if (rawSamples.isEmpty()) rawSamples else {
                    val distFn = BallisticsEngine.dragDecayedDistanceFn(
                        bullet, atmosphere,
                        maxTS = rawSamples.maxOf { it.timeS } + 0.1
                    )
                    rawSamples.map { it.copy(downrangeM = distFn(it.timeS)) }
                }
                Logger.i(TAG, "Estimated ${windSamples.size} wind samples " +
                    "(mode=${if (tracer) "TRACER" else "VAPOR"} tof=${"%.2f".format(tofS)}s settle=${"%.2f".format(settleS)}s)")

                val adjustment = AdjustmentCalculator.computeAdjustment(
                    bullet, activeRifle, scope, atmosphere, targetDistanceYd, windSamples
                )
                Logger.i(TAG, "Adjustment: windage=${adjustment.windageDirection} ${adjustment.windageScopeUnits} ${adjustment.scopeUnitLabel}, " +
                    "elevation=${adjustment.elevationDirection} ${adjustment.elevationScopeUnits} ${adjustment.scopeUnitLabel}, " +
                    "wind=${adjustment.estimatedCrosswindMps} m/s (conf=${adjustment.windConfidence}), warnings=${adjustment.warnings.size}")

                AnalysisSession.windSamples = windSamples
                AnalysisSession.adjustment = adjustment
                AnalysisSession.targetDistanceYd = targetDistanceYd
                AnalysisSession.baseFovDeg = fovDeg
                AnalysisSession.cameraZoom = zoom
                AnalysisSession.effectiveFovDeg = effectiveFovDeg
                AnalysisSession.tracerMode = tracer
                AnalysisSession.muzzleVelocityMps = bullet.muzzleVelocityMps
                AnalysisSession.persist(this@CaptureActivity)

                withContext(Dispatchers.Main) {
                    setUiBusy(false)
                    startActivity(Intent(this@CaptureActivity, ResultsActivity::class.java))
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "Analysis failed", t)
                withContext(Dispatchers.Main) {
                    setUiBusy(false)
                    notifyUser("Analysis failed: ${t.message}. See the Log tab for details.")
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
