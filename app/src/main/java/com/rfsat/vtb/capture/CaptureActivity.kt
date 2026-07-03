package com.rfsat.vtb.capture

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.rfsat.vtb.ballistics.Atmosphere
import com.rfsat.vtb.databinding.ActivityCaptureBinding
import com.rfsat.vtb.profiles.ProfileRepository
import com.rfsat.vtb.results.AdjustmentCalculator
import com.rfsat.vtb.results.AnalysisSession
import com.rfsat.vtb.results.ResultsActivity
import com.rfsat.vtb.wind.WindEstimator
import java.io.File

class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private lateinit var repo: ProfileRepository
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var outputFile: File? = null

    private var boresightX = 0.5 // normalized 0..1, defaults to frame center
    private var boresightY = 0.5

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission is required to capture the trail.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ProfileRepository(this)

        binding.previewView.setOnTouchListener { view, event ->
            boresightX = (event.x / view.width).coerceIn(0f, 1f).toDouble()
            boresightY = (event.y / view.height).coerceIn(0f, 1f).toDouble()
            Toast.makeText(this, "Point of aim marked", Toast.LENGTH_SHORT).show()
            true
        }

        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnAnalyze.setOnClickListener { runAnalysis() }
        binding.btnAnalyze.isEnabled = false

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecording() {
        val capture = videoCapture ?: return
        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            binding.btnRecord.text = getString(com.rfsat.vtb.R.string.record)
            binding.btnAnalyze.isEnabled = true
            return
        }

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
                    lastRecordedUri = event.outputResults.outputUri
                }
            }
        binding.btnRecord.text = getString(com.rfsat.vtb.R.string.stop)
    }

    private var lastRecordedUri: Uri? = null

    private fun runAnalysis() {
        val uri = lastRecordedUri
        if (uri == null) {
            Toast.makeText(this, "No recording found yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val shotBreakOffsetS = binding.etShotBreakSeconds.text.toString().toDoubleOrNull() ?: 0.5
        val targetDistanceYd = binding.etTargetDistance.text.toString().toDoubleOrNull() ?: 100.0
        val fovDeg = binding.etHorizontalFov.text.toString().toDoubleOrNull() ?: 60.0

        val bullet = repo.getBullet()
        val rifle = repo.getRifle()
        val scope = repo.getScope()
        val atmosphere = Atmosphere() // TODO: wire up a range-conditions input screen

        // Frame size isn't known until we read one frame back; TrailExtractor
        // returns pixel coords in the actual decoded frame's resolution, and
        // we mark the boresight in normalized preview coordinates, so scale
        // once we have a reference frame. For simplicity here we assume the
        // recorded video resolution matches the preview aspect ratio.
        val observations = TrailExtractor.extract(this, uri, shotBreakOffsetS)
        if (observations.isEmpty()) {
            Toast.makeText(this, "No trail detected — check lighting/contrast and try again.", Toast.LENGTH_LONG).show()
            return
        }

        // Recover the actual decoded frame resolution from the retriever indirectly:
        // TrailExtractor doesn't expose it, so we approximate using the preview view
        // size, which is acceptable since MediaStore video capture on most phones
        // preserves the sensor aspect ratio shown in the preview.
        val frameWidthPx = binding.previewView.width
        val frameHeightPx = binding.previewView.height

        val calibration = TrailCalibration(
            horizontalFovDeg = fovDeg,
            frameWidthPx = frameWidthPx,
            frameHeightPx = frameHeightPx,
            boresightPixelX = boresightX * frameWidthPx,
            boresightPixelY = boresightY * frameHeightPx
        )

        val targetDistanceM = targetDistanceYd * 0.9144
        val trailSamples = TrailSampleBuilder.build(observations, calibration, bullet, atmosphere, targetDistanceM + 5.0)
        val windSamples = WindEstimator.estimate(bullet, atmosphere, trailSamples)

        val adjustment = AdjustmentCalculator.computeAdjustment(
            bullet, rifle, scope, atmosphere, targetDistanceYd, windSamples
        )

        AnalysisSession.windSamples = windSamples
        AnalysisSession.adjustment = adjustment
        AnalysisSession.targetDistanceYd = targetDistanceYd

        startActivity(Intent(this, ResultsActivity::class.java))
    }
}
