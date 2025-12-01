package com.example.teamfabiano

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
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
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class CameraFragment : Fragment() {

    private enum class ValidationState {
        IDLE,           // Looping ghost replay, waiting for user to get in position
        COUNTDOWN,      // Countdown is active, ghost is looping
        VALIDATING,     // Actively comparing poses with synced ghost
        SHOWING_SCORE   // Displaying final score
    }

    private var currentState = ValidationState.IDLE

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var previewView: PreviewView
    private lateinit var poseOverlayView: PoseOverlayView
    private lateinit var similarityScoreTextView: TextView
    private lateinit var countdownTextView: TextView
    private lateinit var feedbackImageView: ImageView

    private var masterRecording: List<SavedPose>? = null
    private var validationScores = mutableListOf<Double>()
    private val handler = Handler(Looper.getMainLooper())
    private var ghostReplayRunnable: Runnable? = null
    private lateinit var userStatus: UserStatus

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
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        userStatus = UserStatus.load(requireContext().filesDir)

        previewView = view.findViewById(R.id.camera_preview)
        poseOverlayView = view.findViewById(R.id.pose_overlay)
        similarityScoreTextView = view.findViewById(R.id.text_similarity_score)
        countdownTextView = view.findViewById(R.id.text_countdown)
        feedbackImageView = view.findViewById(R.id.image_feedback)
        val mainMenuButton = view.findViewById<Button>(R.id.button_main_menu)

        masterRecording = loadMasterRecording("lesson_1_master.json")
        if (masterRecording == null) {
            similarityScoreTextView.text = "Master for lesson 1 not found."
        } else {
            resetValidationState()
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        mainMenuButton.setOnClickListener {
            parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
    }

    private fun startCountdown() {
        currentState = ValidationState.COUNTDOWN
        countdownTextView.visibility = View.VISIBLE

        object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                countdownTextView.text = "${millisUntilFinished / 1000}"
            }

            override fun onFinish() {
                countdownTextView.visibility = View.GONE
                stopGhostReplay()
                startValidation()
            }
        }.start()
    }

    private fun startValidation() {
        currentState = ValidationState.VALIDATING
        validationScores.clear()
        feedbackImageView.visibility = View.VISIBLE
        Log.d("CameraFragment", "Validation started")

        view?.postDelayed({
            stopValidation()
        }, 10000)
    }

    private fun stopValidation() {
        currentState = ValidationState.SHOWING_SCORE
        feedbackImageView.visibility = View.GONE
        poseOverlayView.clearPose(isGhost = true)

        if (validationScores.isNotEmpty()) {
            val averageScore = validationScores.average()
            similarityScoreTextView.text = "Average Match: ${String.format("%.1f", averageScore)}%"

            if (averageScore > 70) {
                val levels = loadLevels()
                userStatus.addXp(10, levels)
                UserStatus.save(requireContext().filesDir, userStatus)
                Toast.makeText(requireContext(), "+10 XP!", Toast.LENGTH_SHORT).show()
            }

        } else {
            similarityScoreTextView.text = "No poses captured for validation."
        }

        view?.postDelayed({ resetValidationState() }, 3000)
    }

    private fun resetValidationState() {
        currentState = ValidationState.IDLE
        similarityScoreTextView.text = "Match the pose to begin"
        startGhostReplay()
    }

    private fun startGhostReplay() {
        if (masterRecording == null || masterRecording!!.isEmpty()) return

        ghostReplayRunnable = object : Runnable {
            var frameIndex = 0
            override fun run() {
                val masterPose = masterRecording!![frameIndex]
                poseOverlayView.updateGhostPose(masterPose.landmarks, masterPose.width, masterPose.height)
                frameIndex = (frameIndex + 1) % masterRecording!!.size
                handler.postDelayed(this, 33) // ~30 fps
            }
        }
        handler.post(ghostReplayRunnable!!)
    }

    private fun stopGhostReplay() {
        ghostReplayRunnable?.let { handler.removeCallbacks(it) }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val options = AccuratePoseDetectorOptions.Builder().setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE).build()
            val poseDetector = PoseDetection.getClient(options)

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis?.setAnalyzer(ContextCompat.getMainExecutor(requireContext())) { imageProxy ->
                if (view == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val mediaImage = imageProxy.image ?: return@setAnalyzer imageProxy.close()
                val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                poseDetector.process(inputImage)
                    .addOnSuccessListener { pose ->
                        if (view == null) return@addOnSuccessListener
                        val landmarks = pose.allPoseLandmarks
                        if (landmarks.isNotEmpty()) {
                            val adjustedWidth = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) mediaImage.width else mediaImage.height
                            val adjustedHeight = if (imageProxy.imageInfo.rotationDegrees % 180 == 0) mediaImage.height else mediaImage.width
                            poseOverlayView.updateLivePose(landmarks, adjustedWidth, adjustedHeight)

                            when (currentState) {
                                ValidationState.IDLE -> checkForDistanceMatch(pose)
                                ValidationState.COUNTDOWN -> { /* Ghost is looping, do nothing */ }
                                ValidationState.VALIDATING -> performValidation(pose)
                                else -> { /* Do nothing for score display */ }
                            }
                        }
                    }
                    .addOnFailureListener { e -> Log.e("PoseDetection", "Failed", e) }
                    .addOnCompleteListener { imageProxy.close() }
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e("CameraFragment", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun checkForDistanceMatch(livePose: Pose) {
        val master = masterRecording ?: return
        if (master.isEmpty()) return

        val liveScale = getPoseScale(livePose.allPoseLandmarks) ?: return
        val masterScale = getPoseScaleFromSaved(master.first().landmarks) ?: return

        val distancePercentage = abs(liveScale - masterScale) / masterScale
        if (distancePercentage < 0.2) { // 20% threshold
            startCountdown()
        }
    }

    private fun performValidation(livePose: Pose) {
        val master = masterRecording ?: return
        if (master.isEmpty() || validationScores.size >= master.size) {
            poseOverlayView.clearPose(isGhost = true)
            return
        }

        val masterPose = master[validationScores.size]
        poseOverlayView.updateGhostPose(masterPose.landmarks, masterPose.width, masterPose.height)

        val similarity = calculateSimilarity(livePose, masterPose)
        similarityScoreTextView.text = "Match: ${String.format("%.1f", similarity)}%"
        validationScores.add(similarity)

        if (similarity > 50) {
            feedbackImageView.setImageResource(R.drawable.ic_correct)
        } else {
            feedbackImageView.setImageResource(R.drawable.ic_error)
        }
    }

    private fun getPoseScale(landmarks: List<PoseLandmark>): Float? {
        val leftShoulder = landmarks.find { it.landmarkType == PoseLandmark.LEFT_SHOULDER } ?: return null
        val rightShoulder = landmarks.find { it.landmarkType == PoseLandmark.RIGHT_SHOULDER } ?: return null

        if (leftShoulder.inFrameLikelihood < 0.7f || rightShoulder.inFrameLikelihood < 0.7f) return null

        return sqrt((leftShoulder.position3D.x - rightShoulder.position3D.x).pow(2) + (leftShoulder.position3D.y - rightShoulder.position3D.y).pow(2) + (leftShoulder.position3D.z - rightShoulder.position3D.z).pow(2))
    }

    private fun getPoseScaleFromSaved(landmarks: List<SavedLandmark>): Float? {
        val leftShoulder = landmarks.find { it.type == PoseLandmark.LEFT_SHOULDER } ?: return null
        val rightShoulder = landmarks.find { it.type == PoseLandmark.RIGHT_SHOULDER } ?: return null
        return sqrt((leftShoulder.x - rightShoulder.x).pow(2) + (leftShoulder.y - rightShoulder.y).pow(2) + (leftShoulder.z - rightShoulder.z).pow(2))
    }

    private fun loadMasterRecording(filename: String): List<SavedPose>? {
        val file = File(requireContext().filesDir, filename)
        if (!file.exists()) return null
        return try {
            val json = file.readText()
            val type = object : TypeToken<List<SavedPose>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadLevels(): List<Level> {
        return try {
            val json = requireContext().assets.open("levels.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, List<Level>>>() {}.type
            val data: Map<String, List<Level>> = Gson().fromJson(json, type)
            data["levels"] ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun calculateSimilarity(livePose: Pose, masterPose: SavedPose): Double {
        val liveLandmarks = normalize(livePose.allPoseLandmarks.map { 
            SavedLandmark(it.landmarkType, it.position3D.x, it.position3D.y, it.position3D.z, it.inFrameLikelihood)
        }) ?: return 0.0
        val masterLandmarks = normalize(masterPose.landmarks) ?: return 0.0

        // This is a tunable value. A lower value is stricter, a higher value is more forgiving.
        val maxAcceptableDistance = 0.5 
        var totalScore = 0.0
        var landmarkCount = 0

        for (masterLm in masterLandmarks) {
            if (!isMajorLandmark(masterLm.type)) continue
            landmarkCount++

            liveLandmarks.find { it.type == masterLm.type }?.let { liveLm ->
                val distance = sqrt(
                    (liveLm.x - masterLm.x).pow(2) +
                    (liveLm.y - masterLm.y).pow(2) +
                    (liveLm.z - masterLm.z).pow(2)
                )
                
                // The score for a single joint is 1 if distance is 0, and decreases to 0 as it approaches maxAcceptableDistance.
                val score = (1.0 - (distance / maxAcceptableDistance)).coerceIn(0.0, 1.0)
                totalScore += score
            }
        }

        return if (landmarkCount > 0) (totalScore / landmarkCount) * 100 else 0.0
    }

    private fun isMajorLandmark(type: Int): Boolean {
        return when (type) {
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE -> true
            else -> false
        }
    }

    private fun normalize(landmarks: List<SavedLandmark>): List<SavedLandmark>? {
        val leftShoulder = landmarks.find { it.type == PoseLandmark.LEFT_SHOULDER && (it.likelihood ?: 1.0f) > 0.7f } ?: return null
        val rightShoulder = landmarks.find { it.type == PoseLandmark.RIGHT_SHOULDER && (it.likelihood ?: 1.0f) > 0.7f } ?: return null

        val centerX = (leftShoulder.x + rightShoulder.x) / 2
        val centerY = (leftShoulder.y + rightShoulder.y) / 2
        val centerZ = (leftShoulder.z + rightShoulder.z) / 2

        val scale = sqrt((leftShoulder.x - rightShoulder.x).pow(2) + (leftShoulder.y - rightShoulder.y).pow(2) + (leftShoulder.z - rightShoulder.z).pow(2))
        if (scale < 1e-6) return null

        return landmarks.map {
            val normalizedX = (it.x - centerX) / scale
            val normalizedY = (it.y - centerY) / scale
            val normalizedZ = (it.z - centerZ) / scale
            SavedLandmark(it.type, normalizedX, normalizedY, normalizedZ, it.likelihood)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopGhostReplay()
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
    }

    data class RecordedPose(val landmarks: List<PoseLandmark>, val frameWidth: Int, val frameHeight: Int)
    data class SavedLandmark(val type: Int, val x: Float, val y: Float, val z: Float, val likelihood: Float? = 1.0f)
    data class SavedPose(val landmarks: List<SavedLandmark>, val width: Int, val height: Int)

    companion object {
        private const val ARG_LESSON_ID = "lesson_id"
        fun newInstance(lessonId: Int) = CameraFragment().apply { arguments = Bundle().apply { putInt(ARG_LESSON_ID, lessonId) } }
    }
}
