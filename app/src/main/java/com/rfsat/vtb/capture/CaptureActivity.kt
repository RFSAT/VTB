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
    // v20.23: scope Wi-Fi stream capture (EXPERIMENTAL)
    private var streamRecorder: RtspStreamRecorder? = null
    private var scopeWifiNetwork: android.net.Network? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

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
            maybeOfferScopeGeometry()
        }
    }

    /**
     * v20.18: clips recorded BY a digital scope (ATN X-Sight/ThOR etc.) have
     * the SCOPE's field of view, not the phone camera's. If the active scope
     * profile declares its base-magnification FOV, offer to apply it: the
     * FOV field becomes the 1x-equivalent (fovAtBase * zoomMin) and the Zoom
     * field the magnification used while recording, so analysis geometry
     * (FOV / zoom) is exactly the scope's true field of view.
     */
    private fun maybeOfferScopeGeometry() {
        val scope = com.rfsat.vtb.profiles.ProfileRepository(this).getScope()
        if (scope.fovAtBaseDeg <= 0.0) {
            notifyUser("Video imported — ready to analyze. Note: clips recorded with stabilization ON can bias wind estimates.")
            return
        }
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Magnification during recording"
            setText(scope.zoomMin.toString())
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Video source")
            .setMessage("Was this clip recorded by “${scope.name}”? If so, enter the magnification used and the analysis geometry is set from the scope's optics.")
            .setView(input)
            .setPositiveButton("From scope") { _, _ ->
                val z = input.text.toString().toDoubleOrNull()?.coerceIn(scope.zoomMin, scope.zoomMax) ?: scope.zoomMin
                val fovAt1x = scope.fovAtBaseDeg * scope.zoomMin
                binding.etHorizontalFov.setText(String.format("%.2f", fovAt1x))
                binding.etZoom.setText(String.format("%.1f", z))
                Logger.i(TAG, "Scope-source import: ${scope.name} fovBase=${scope.fovAtBaseDeg}° zoom=$z -> fov@1x=${"%.2f".format(fovAt1x)}°")
                notifyUser("Scope geometry applied (${scope.name}, ${z}×). Ready to analyze.")
            }
            .setNegativeButton("Phone camera") { _, _ ->
                notifyUser("Video imported — ready to analyze. Note: clips recorded with stabilization ON can bias wind estimates.")
            }
            .show()
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

        binding.btnRecord.setOnClickListener {
            if (scopeSourceSelected()) toggleStreamRecording() else toggleRecording()
        }
        // v19.9: the SLIDER commands the camera (unit steps); the Zoom (x)
        // field is descriptive again — auto-echoed from the camera, manual
        // only for imported clips.
        binding.slZoom.addOnChangeListener { _, value, fromUser ->
            binding.tvZoomLabel.text = "Camera zoom: ${value.toInt()}×"
            if (fromUser) {
                getSharedPreferences("vtb_capture_fields", MODE_PRIVATE)
                    .edit().putInt("capture_zoom", value.toInt()).apply()
                applyCaptureZoom(value.toDouble())
            }
        }
        binding.btnImportVideo.setOnClickListener { importVideoLauncher.launch("video/*") }
        binding.btnAnalyze.setOnClickListener { runAnalysis() }
        binding.btnAnalyze.isEnabled = false

        binding.btnArm.setOnClickListener { toggleArm() }
        setupCaptureSource()
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

    /**
     * v19.0 accuracy/performance camera setup:
     *  - STABILIZATION OFF (EIS + OIS). Stabilization rotates and crops
     *    frames to cancel handshake — motion the extractor reads as trail
     *    drift. On a scope-clamped phone it removes signal, not shake, and
     *    is a prime suspect for artefact winds.
     *  - 60 fps when the sensor supports it: doubles the tracer
     *    estimator's in-flight sample count.
     * Best-effort: any failure leaves the default pipeline untouched.
     */
    /** v20.2: a fling on the zoom slider must move the slider, not tabs. */
    override fun swipeExemptViews(): List<android.view.View> = listOf(binding.slZoom)

    private var camera2Control: androidx.camera.camera2.interop.Camera2CameraControl? = null
    private var boundCamera: androidx.camera.core.Camera? = null
    private var supportedFixedFps: List<Int> = emptyList()

    /**
     * v19.8: the Zoom (x) field now COMMANDS the camera as well as
     * describing the clip. Setting zoomRatio on a logical multi-camera
     * engages the optical telephoto automatically at its native ratio
     * (3x on the S24) and digital-crops between/beyond — so the phone
     * frames close to what the scope sees, and drift resolution scales
     * with the magnification. Analysis geometry needs NO special-casing:
     * Camera2 defines zoomRatio as an exact FOV divisor, which is
     * precisely the base-FOV/zoom recombination the analysis already does.
     */
    private fun applyCaptureZoom(requested: Double) {
        val cam = boundCamera ?: return
        val state = cam.cameraInfo.zoomState.value
        val lo = (state?.minZoomRatio ?: 1.0f).toDouble()
        val hi = (state?.maxZoomRatio ?: 8.0f).toDouble()
        val applied = requested.coerceIn(lo, hi)
        try {
            cam.cameraControl.setZoomRatio(applied.toFloat())
            Logger.i(TAG, "Capture zoom: %.0fx applied (device range %.1f-%.1fx)"
                .format(applied, lo, hi))
        } catch (t: Throwable) {
            Logger.w(TAG, "Zoom apply failed: ${t.message}")
        }
    }

    /** v19.9: bound the slider by the device's REAL zoom range (unit steps
     *  from 1x to floor(max)), restore the persisted magnification, apply. */
    private var zoomSliderReady = false
    private fun setupZoomSlider(maxZoomRatio: Float) {
        if (zoomSliderReady) return
        zoomSliderReady = true
        val maxStep = kotlin.math.floor(maxZoomRatio).toInt().coerceAtLeast(1)
        if (maxStep < 2) {
            binding.slZoom.visibility = android.view.View.GONE
            binding.tvZoomLabel.visibility = android.view.View.GONE
            Logger.i(TAG, "Zoom slider hidden — device max zoom %.1fx".format(maxZoomRatio))
            return
        }
        binding.slZoom.valueTo = maxStep.toFloat()
        val saved = getSharedPreferences("vtb_capture_fields", MODE_PRIVATE)
            .getInt("capture_zoom", 1).coerceIn(1, maxStep)
        binding.slZoom.value = saved.toFloat()
        binding.tvZoomLabel.text = "Camera zoom: ${saved}×"
        Logger.i(TAG, "Zoom slider: 1-${maxStep}x (device max %.1fx), restored ${saved}x".format(maxZoomRatio))
        applyCaptureZoom(saved.toDouble())
    }

    /**
     * v19.7 (accuracy): AE/AWB lock while recording. The extractor scores
     * luminance DIFFERENCES against a reference frame — auto-exposure
     * reacting mid-clip (muzzle flash, cloud, recoil toward brighter sky)
     * brightens whole frames and reads as false trail signal. Locking at
     * record-start makes the static-background assumption actually true.
     */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun setExposureLock(locked: Boolean) {
        val c = camera2Control ?: return
        try {
            c.addCaptureRequestOptions(
                androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_LOCK, locked)
                    .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK, locked)
                    .build()
            )
            Logger.i(TAG, if (locked) "AE/AWB locked for recording" else "AE/AWB unlocked")
        } catch (t: Throwable) {
            Logger.w(TAG, "Exposure lock unavailable: ${t.message}")
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun configureCaptureForAnalysis(camera: androidx.camera.core.Camera) {
        try {
            camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl)
            val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.cameraInfo)
            val ranges = info.getCameraCharacteristic(
                android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )
            // v19.8: user-selectable frame rate. Offer exactly the FIXED
            // rates the sensor supports in a normal session (lower==upper;
            // variable ranges like 15-30 are AE's business, not ours) —
            // 120 fps appears only on devices that truly expose it here.
            supportedFixedFps = ranges?.filter { it.lower == it.upper }
                ?.map { it.lower }?.distinct()?.sorted() ?: emptyList()
            setupFpsSpinner()
            applyCaptureTuning()
        } catch (t: Throwable) {
            Logger.w(TAG, "Capture tuning unavailable: ${t.message}")
        }
    }

    private fun selectedFps(): Int =
        getSharedPreferences("vtb_capture_fields", MODE_PRIVATE).getInt("capture_fps", 0)

    private fun setupFpsSpinner() {
        val labels = listOf("Auto") + supportedFixedFps.map { "$it fps" }
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spFrameRate.adapter = adapter
        val saved = selectedFps()
        val idx = if (saved > 0) supportedFixedFps.indexOf(saved).let { if (it >= 0) it + 1 else 0 } else 0
        binding.spFrameRate.setSelection(idx, false)
        binding.spFrameRate.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    val fps = if (pos == 0) 0 else supportedFixedFps[pos - 1]
                    if (fps != selectedFps()) {
                        getSharedPreferences("vtb_capture_fields", MODE_PRIVATE)
                            .edit().putInt("capture_fps", fps).apply()
                        applyCaptureTuning()
                    }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
    }

    /** Stabilization OFF always; plus the chosen fixed frame rate when the
     *  sensor supports it. (Exposure lock is layered separately with
     *  addCaptureRequestOptions so it survives re-tuning order.) */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyCaptureTuning() {
        val c = camera2Control ?: return
        try {
            val fps = selectedFps().takeIf { it in supportedFixedFps }
            val opts = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
                .apply {
                    if (fps != null) setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(fps, fps)
                    )
                }
                .build()
            c.setCaptureRequestOptions(opts)
            Logger.i(TAG, "Capture configured: stabilization OFF, fps=${fps ?: "auto"} (supported fixed: $supportedFixedFps)")
        } catch (t: Throwable) {
            Logger.w(TAG, "Capture tuning unavailable: ${t.message}")
        }
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

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            // v19.0: FHD preferred — high-frame-rate modes live at FHD, and
            // 640 px analysis gains nothing from 4K frames that cost 4x the
            // decode time.
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    androidx.camera.video.QualitySelector.from(
                        androidx.camera.video.Quality.FHD,
                        androidx.camera.video.FallbackStrategy.higherQualityOrLowerThan(
                            androidx.camera.video.Quality.FHD
                        )
                    )
                )
                .build()
            val capture = VideoCapture.withOutput(recorder)
            videoCapture = capture

            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            boundCamera = camera
            configureCaptureForAnalysis(camera)
            // Auto-FOV: fill the field from the real optics now and whenever
            // the zoom changes (a zoom change invalidates any manual value).
            // The field stays editable for imported clips from other devices.
            camera.cameraInfo.zoomState.observe(this) { zoomState ->
                // v19.9: first emission carries the real device range —
                // bound the slider by it and restore the saved magnification.
                zoomState?.maxZoomRatio?.let { setupZoomSlider(it) }
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

    override fun onResume() {
        super.onResume()
        setupCaptureSource() // scope may have changed in Settings
    }

    // ------------- v20.23: scope Wi-Fi stream capture (EXPERIMENTAL) -------------
    // ATN digital scopes run an RTSP service on their own Wi-Fi hotspot (TCP
    // 554; community-verified). Connect the phone to the scope's Wi-Fi first;
    // recording pulls the stream into a local MP4 that feeds the normal
    // analysis pipeline. The exact stream path varies by model, so candidate
    // paths are auto-probed and the whole handshake is logged.

    private fun streamPrefs() = getSharedPreferences("vtb_capture_fields", MODE_PRIVATE)

    private fun scopeSourceSelected(): Boolean =
        binding.rowCaptureSource.visibility == android.view.View.VISIBLE &&
            binding.spCaptureSource.selectedItemPosition == 1

    private fun setupCaptureSource() {
        val scope = com.rfsat.vtb.profiles.ProfileRepository(this).getScope()
        if (!scope.streamCapable) {
            binding.rowCaptureSource.visibility = android.view.View.GONE
            binding.tvStreamStatus.visibility = android.view.View.GONE
            return
        }
        binding.rowCaptureSource.visibility = android.view.View.VISIBLE
        binding.tvStreamStatus.visibility = android.view.View.VISIBLE
        val items = listOf("Phone camera", "${scope.name} (Wi-Fi stream)")
        val a = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spCaptureSource.adapter = a
        binding.spCaptureSource.setSelection(streamPrefs().getInt("capture_source", 0).coerceIn(0, 1))
        updateStreamStatusIdle()
        binding.spCaptureSource.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                streamPrefs().edit().putInt("capture_source", pos).apply()
                binding.btnArm.isEnabled = pos == 0 // audio auto-trigger is a camera-path feature (v1)
                updateStreamStatusIdle()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        binding.tvStreamStatus.setOnClickListener { if (streamRecorder == null) editStreamUrl() }
    }

    private fun streamUrl(): String =
        streamPrefs().getString("stream_url", "rtsp://192.168.1.1:554") ?: "rtsp://192.168.1.1:554"

    private fun updateStreamStatusIdle() {
        binding.tvStreamStatus.text = if (scopeSourceSelected())
            "Scope stream: ${streamUrl()} (connect phone Wi-Fi to the scope; tap to edit URL)"
        else "Phone camera selected"
    }

    private fun editStreamUrl() {
        val input = android.widget.EditText(this).apply { setText(streamUrl()) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Scope stream URL")
            .setMessage("Default is the ATN hotspot address. A path can be appended (e.g. /stream0); without one, common paths are probed automatically.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                streamPrefs().edit().putString("stream_url", input.text.toString().trim()).apply()
                updateStreamStatusIdle()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun toggleStreamRecording() {
        val active = streamRecorder
        if (active != null) {
            active.stop()
            streamRecorder = null
            releaseScopeNetwork()
            binding.btnRecord.text = getString(com.rfsat.vtb.R.string.record)
            val f = java.io.File(cacheDir, "scope_stream.mp4")
            if (active.framesWritten > 0 && f.length() > 0) {
                pendingUri = Uri.fromFile(f)
                pendingReferenceBitmap = null
                binding.btnAnalyze.isEnabled = true
                Logger.i(TAG, "Scope stream saved: ${active.framesWritten} frames, ${f.length()} bytes")
                maybeOfferScopeGeometry()
            } else {
                notifyUser("No video received from the scope — see the Log tab and share it; the RTSP handshake there identifies the fix. ${active.lastError ?: ""}")
            }
            return
        }
        // Start: bind to the scope's Wi-Fi network so traffic isn't routed to
        // mobile data (the scope AP has no internet, and Android avoids such
        // networks by default). Falls back to default routing after 5 s.
        binding.btnRecord.text = getString(com.rfsat.vtb.R.string.stop)
        binding.tvStreamStatus.text = "Acquiring Wi-Fi network…"
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val req = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        fun begin(net: android.net.Network?) {
            if (!started.compareAndSet(false, true)) return
            scopeWifiNetwork = net
            runOnUiThread { startStreamRecorder(net) }
        }
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = begin(network)
        }
        networkCallback = cb
        runCatching { cm.requestNetwork(req, cb) }
            .onFailure { begin(null) }
        binding.root.postDelayed({ begin(null) }, 5000) // no Wi-Fi callback -> try default route
    }

    private fun startStreamRecorder(net: android.net.Network?) {
        if (net == null) Logger.w(TAG, "No Wi-Fi network callback; using default routing (stream may fail if mobile data is active)")
        val f = java.io.File(cacheDir, "scope_stream.mp4").apply { delete() }
        val rec = RtspStreamRecorder(streamUrl(), f, net) { msg ->
            runOnUiThread { binding.tvStreamStatus.text = "Scope stream: $msg" }
        }
        streamRecorder = rec
        Logger.i(TAG, "Scope stream recording started: ${streamUrl()}")
        rec.start()
    }

    private fun releaseScopeNetwork() {
        networkCallback?.let { cb ->
            runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        scopeWifiNetwork = null
    }

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

        setExposureLock(true) // v19.7: freeze AE/AWB for the whole clip
        recording = capture.output.prepareRecording(this, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    setExposureLock(false)
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
        val manualShotBreakS = binding.etShotBreakSeconds.text.toString().toDoubleOrNull() ?: 0.5
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
                Logger.i(TAG, "Analysis starting: uri=$uri shotBreak(field)=${manualShotBreakS}s target=${targetDistanceYd}yd " +
                    "baseFov=${"%.1f".format(fovDeg)}deg zoom=${"%.1f".format(zoom)}x -> effFov=${"%.2f".format(effectiveFovDeg)}deg " +
                    "externalRef=${referenceBitmap != null}")

                val activeRifle = repo.getRifle()
                val scope = repo.getScope()
                val atmosphere = com.rfsat.vtb.environment.EnvironmentManager.current.atmosphere
                Logger.i(TAG, "Analysis environment: ${com.rfsat.vtb.environment.EnvironmentManager.describe()}")
                // v20.1: MV corrected to ambient temperature (per-bullet
                // coefficient, Settings > Bullet). One adjusted copy feeds
                // EVERYTHING downstream — engine, estimators, recorded MV.
                val rawBullet = repo.getBullet()
                val bullet = rawBullet.adjustedForTemperature(atmosphere.temperatureC)
                if (bullet !== rawBullet) Logger.i(TAG,
                    "MV temp-adjusted: %.1f -> %.1f m/s (%.1f degC vs ref %.1f, coeff %.2f m/s/degC)"
                        .format(rawBullet.muzzleVelocityMps, bullet.muzzleVelocityMps,
                            atmosphere.temperatureC, rawBullet.mvRefTempC, rawBullet.mvTempCoeffMpsPerC))

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

                // v19.7 (accuracy): pin the shot break to the muzzle report in
                // the clip's own audio. An error in t0 shifts the settle window
                // and biases the tracer lag rule directly; the report is a
                // millisecond-sharp transient, and this works for imported
                // clips exactly as for recorded ones. Falls back to the field
                // value when the clip has no audio or no distinct transient.
                val detectedShotS = AudioShotDetector.detectShotTimeS(localFile.absolutePath)
                val shotBreakOffsetS = detectedShotS ?: manualShotBreakS
                Logger.i(TAG, if (detectedShotS != null)
                    "Shot break from AUDIO: ${"%.3f".format(detectedShotS)} s (field ${"%.2f".format(manualShotBreakS)} s)"
                else
                    "Shot break from field: ${"%.2f".format(manualShotBreakS)} s (no audio transient)")

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
                // v20.18: a tracked pellet is geometrically the same problem
                // as a tracked tracer point (lag rule), just without the red
                // signature — it shares the tracer estimator path.
                val pointTracked = tracer || bullet.isPellet
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
                    mode = when {
                        tracer -> TrailExtractor.Mode.TRACER
                        bullet.isPellet -> TrailExtractor.Mode.PELLET
                        else -> TrailExtractor.Mode.VAPOR
                    }
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

                val rawSamples = if (pointTracked) {
                    com.rfsat.vtb.wind.TracerWindEstimator.estimate(
                        calibration, observations, bullet, atmosphere,
                        zeroDistanceM = activeRifle.zeroDistanceM,
                        sightHeightM = sightHeightM,
                        targetDistanceM = targetDistanceM
                    )
                } else {
                    val windScale = getSharedPreferences("vtb_wind_cal", MODE_PRIVATE)
                        .getFloat("scale", 1.0f).toDouble()
                    WindEstimator.estimate(
                        calibration, observations, targetDistanceM, settleTimeS = settleS,
                        windScale = windScale
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
                com.rfsat.vtb.profiles.ProfileRepository(this@CaptureActivity).let { rp ->
                    AnalysisSession.profileFingerprint =
                        "${rp.getRifle().name}|${rp.getBullet().name}|${rp.getScope().name}"
                }
                AnalysisSession.persist(this@CaptureActivity)
                AnalysisSession.appendHistory(this@CaptureActivity)

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
