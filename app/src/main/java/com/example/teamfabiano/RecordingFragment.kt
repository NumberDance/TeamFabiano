package com.example.teamfabiano

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.gson.Gson
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.io.File

class RecordingFragment : Fragment() {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var previewView: PreviewView
    private lateinit var poseOverlayView: PoseOverlayView
    private lateinit var countdownTextView: TextView

    private var isRecording = false
    private val recordedPoses = mutableListOf<CameraFragment.RecordedPose>()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(requireContext(), "Camera permission is required to continue.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_recording, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.camera_preview)
        poseOverlayView = view.findViewById(R.id.pose_overlay)
        countdownTextView = view.findViewById(R.id.text_countdown)
        val recordButton = view.findViewById<Button>(R.id.button_record_master)
        val mainMenuButton = view.findViewById<Button>(R.id.button_main_menu)

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        recordButton.setOnClickListener { startMasterRecordingFlow(it as Button) }

        mainMenuButton.setOnClickListener {
            parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
    }

    private fun startMasterRecordingFlow(button: Button) {
        button.isEnabled = false
        countdownTextView.visibility = View.VISIBLE

        object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                countdownTextView.text = "${millisUntilFinished / 1000}"
            }

            override fun onFinish() {
                countdownTextView.visibility = View.GONE
                startRecording(button)
            }
        }.start()
    }

    private fun startRecording(button: Button) {
        isRecording = true
        recordedPoses.clear()
        Log.d("RecordingFragment", "Recording started")

        // Stop recording after 10 seconds
        view?.postDelayed({
            stopRecording(button)
        }, 10000)
    }

    private fun stopRecording(button: Button) {
        isRecording = false
        button.isEnabled = true
        Log.d("RecordingFragment", "Recording stopped. Frames: ${recordedPoses.size}")

        val savedPoses = recordedPoses.map { pose ->
            val savedLandmarks = pose.landmarks.map { lm ->
                CameraFragment.SavedLandmark(lm.landmarkType, lm.position3D.x, lm.position3D.y, lm.position3D.z)
            }
            CameraFragment.SavedPose(savedLandmarks, pose.frameWidth, pose.frameHeight)
        }

        saveAndExport(savedPoses, "lesson_1_master.json")
        Toast.makeText(requireContext(), "Master recording for lesson 1 saved.", Toast.LENGTH_SHORT).show()
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val options = AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                .build()
            val poseDetector = PoseDetection.getClient(options)

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis?.setAnalyzer(ContextCompat.getMainExecutor(requireContext())) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    poseDetector.process(inputImage)
                        .addOnSuccessListener { pose ->
                            if (view == null) return@addOnSuccessListener
                            val landmarks = pose.allPoseLandmarks
                            if (landmarks.isNotEmpty()) {
                                val isPortrait = imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270
                                val adjustedWidth = if (isPortrait) mediaImage.height else mediaImage.width
                                val adjustedHeight = if (isPortrait) mediaImage.width else mediaImage.height

                                poseOverlayView.updateLivePose(landmarks, adjustedWidth, adjustedHeight)

                                if (isRecording) {
                                    recordedPoses.add(CameraFragment.RecordedPose(landmarks, adjustedWidth, adjustedHeight))
                                }
                            }
                        }
                        .addOnFailureListener { e -> Log.e("RecordingFragment", "Failed", e) }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e("RecordingFragment", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun saveAndExport(poses: List<CameraFragment.SavedPose>, filename: String) {
        val gson = Gson()
        val json = gson.toJson(poses)

        // Save to internal storage (for the app to use)
        val internalFile = File(requireContext().filesDir, filename)
        internalFile.writeText(json)
        Log.d("RecordingFragment", "Saved ${poses.size} poses to ${internalFile.absolutePath}")

        // Save a copy to public Downloads directory
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val externalFile = File(downloadsDir, "lesson_1_master_copy.json")
            externalFile.writeText(json)
            Log.d("RecordingFragment", "Exported copy to ${externalFile.absolutePath}")
            Toast.makeText(requireContext(), "Copy saved in Downloads folder", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("RecordingFragment", "Error exporting file", e)
            Toast.makeText(requireContext(), "Could not save copy to Downloads.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
    }
}
