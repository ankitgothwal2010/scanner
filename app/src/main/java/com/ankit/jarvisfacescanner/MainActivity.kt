package com.ankit.jarvisfacescanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.ankit.jarvisfacescanner.camera.CameraManager
import com.ankit.jarvisfacescanner.camera.RtspStreamManager
import com.ankit.jarvisfacescanner.databinding.ActivityMainBinding
import com.ankit.jarvisfacescanner.ml.FaceDetectorEngine
import com.ankit.jarvisfacescanner.ml.FaceRecognitionEngine
import com.ankit.jarvisfacescanner.ml.RecognitionResult
import com.ankit.jarvisfacescanner.storage.EncryptedPreferencesManager
import com.ankit.jarvisfacescanner.storage.ProfileRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: EncryptedPreferencesManager
    private lateinit var repository: ProfileRepository

    private var cameraManager: CameraManager? = null
    private var rtspStreamManager: RtspStreamManager? = null

    private lateinit var detector: FaceDetectorEngine
    private lateinit var recognizer: FaceRecognitionEngine

    private val isScanning = AtomicBoolean(false)
    private val isProcessingFrame = AtomicBoolean(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            setupActiveCamera()
        } else {
            Toast.makeText(this, "Camera permission required for local scanning", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = EncryptedPreferencesManager(this)
        repository = ProfileRepository(this)
        detector = FaceDetectorEngine()
        recognizer = FaceRecognitionEngine(this)

        setupListeners()
        checkPermissionsAndStart()
    }

    private fun setupListeners() {
        binding.btnToggleScan.setOnClickListener {
            if (isScanning.get()) {
                isScanning.set(false)
                binding.btnToggleScan.text = getString(R.string.btn_start_scan)
                binding.reticleOverlay.updateResults(emptyList(), 0, 0)
                binding.tvTelemetry.text = "SCANNER PAUSED // OFFLINE ENGINE IDLE"
            } else {
                isScanning.set(true)
                binding.btnToggleScan.text = getString(R.string.btn_stop_scan)
                binding.tvTelemetry.text = "SCANNING ACTIVE // AWAITING TARGET..."
            }
        }

        binding.btnEnroll.setOnClickListener {
            startActivity(Intent(this, EnrollActivity::class.java))
        }

        binding.btnProfiles.setOnClickListener {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupActiveCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupActiveCamera() {
        val mode = prefsManager.getCameraSource()
        if (mode == "TAPO") {
            binding.cameraPreview.visibility = View.GONE
            binding.rtspTextureView.visibility = View.VISIBLE
            cameraManager?.stopCamera()

            val rtspUrl = prefsManager.getRtspUrl()
            if (rtspUrl.isBlank()) {
                binding.tvStatusBanner.text = "TAPO CONFIG MISSING // OPEN SETTINGS"
                return
            }

            rtspStreamManager = RtspStreamManager(
                this,
                binding.rtspTextureView,
                onFrameSampled = { bitmap -> handleIncomingFrame(bitmap) },
                onStatusChanged = { status -> runOnUiThread { binding.tvStatusBanner.text = status } }
            )
            rtspStreamManager?.startStream(rtspUrl)
        } else {
            binding.rtspTextureView.visibility = View.GONE
            binding.cameraPreview.visibility = View.VISIBLE
            rtspStreamManager?.stopStream()

            cameraManager = CameraManager(this, this, binding.cameraPreview) { bitmap ->
                handleIncomingFrame(bitmap)
            }
            cameraManager?.startCamera()
            binding.tvStatusBanner.text = "PHONE CAMERA ONLINE // HELIO G70"
        }
    }

    private fun handleIncomingFrame(bitmap: Bitmap) {
        if (!isScanning.get() || isProcessingFrame.get()) return

        isProcessingFrame.set(true)
        val startTime = System.currentTimeMillis()

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val faces = detector.detectFaces(bitmap)
                val profiles = repository.getAllProfiles()
                val threshold = prefsManager.getThreshold()
                val results = mutableListOf<RecognitionResult>()

                for (face in faces) {
                    val cropped = recognizer.cropFace(bitmap, face.boundingBox)
                    if (cropped != null) {
                        val embedding = recognizer.extractEmbedding(cropped)
                        val (name, similarity) = recognizer.findBestMatch(embedding, profiles, threshold)
                        val isKnown = name != "UNKNOWN PERSON"

                        results.add(
                            RecognitionResult(
                                boundingBox = face.boundingBox,
                                matchedName = name,
                                confidenceEstimate = similarity,
                                isKnown = isKnown,
                                embedding = embedding
                            )
                        )
                    }
                }

                val latency = System.currentTimeMillis() - startTime
                withContext(Dispatchers.Main) {
                    binding.reticleOverlay.updateResults(results, bitmap.width, bitmap.height)
                    if (results.isNotEmpty()) {
                        val primary = results[0]
                        binding.tvTelemetry.text =
                            "LATENCY: ${latency}ms | TARGET: ${primary.matchedName} | EST.CONF: ${(primary.confidenceEstimate * 100).toInt()}%"
                    } else {
                        binding.tvTelemetry.text = "LATENCY: ${latency}ms | TARGETS: 0 DETECTED | ENGINE: OK"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isProcessingFrame.set(false)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupActiveCamera()
    }

    override fun onPause() {
        super.onPause()
        cameraManager?.stopCamera()
        rtspStreamManager?.stopStream()
    }

    override fun onDestroy() {
        super.onDestroy()
        detector.stop()
        recognizer.close()
    }
}
