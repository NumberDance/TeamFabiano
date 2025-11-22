package com.example.teamfabiano

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.teamfabiano.CameraFragment.SavedLandmark // Important: Import the nested class
import com.google.mlkit.vision.pose.PoseLandmark

class PoseOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val liveSmoothedPoints = mutableMapOf<Int, PointF>()
    private val ghostSmoothedPoints = mutableMapOf<Int, PointF>()
    private var imageWidth = 1
    private var imageHeight = 1
    private var viewWidth = 1
    private var viewHeight = 1
    private val alpha = 0.3f

    // Takes live data from ML Kit
    fun updateLivePose(landmarks: List<PoseLandmark>, width: Int, height: Int) {
        updateSmoothedPoints(
            landmarks.map { PointF(it.position.x, it.position.y) },
            liveSmoothedPoints,
            landmarks.map { it.inFrameLikelihood },
            width,
            height
        )
        invalidate()
    }

    // Takes data from our saved JSON file
    fun updateGhostPose(landmarks: List<SavedLandmark>, width: Int, height: Int) {
        updateSmoothedPoints(
            landmarks.map { PointF(it.x, it.y) }, // Use x,y from SavedLandmark
            ghostSmoothedPoints,
            null, // No likelihood for saved data
            width,
            height,
            isGhost = true
        )
        invalidate()
    }

    private fun updateSmoothedPoints(
        rawPoints: List<PointF>,
        smoothedPoints: MutableMap<Int, PointF>,
        likelihoods: List<Float>?,
        width: Int,
        height: Int,
        isGhost: Boolean = false
    ) {
        imageWidth = width
        imageHeight = height
        val scaleX = viewWidth.toFloat() / imageWidth.toFloat()
        val scaleY = viewHeight.toFloat() / imageHeight.toFloat()

        rawPoints.forEachIndexed { index, point ->
            // For live poses, only draw points with good confidence
            if (!isGhost && (likelihoods == null || likelihoods[index] < 0.3f)) {
                smoothedPoints.remove(index)
                return@forEachIndexed
            }

            val rawX = point.x * scaleX
            val rawY = point.y * scaleY

            val prev = smoothedPoints[index]
            val newX = if (prev != null) alpha * rawX + (1 - alpha) * prev.x else rawX
            val newY = if (prev != null) alpha * rawY + (1 - alpha) * prev.y else rawY

            smoothedPoints[index] = PointF(newX, newY)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        viewWidth = w
        viewHeight = h
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw the ghost pose first, so the live pose is on top
        drawPose(canvas, ghostSmoothedPoints, true)
        drawPose(canvas, liveSmoothedPoints, false)
    }

    private fun drawPose(canvas: Canvas, points: Map<Int, PointF>, isGhost: Boolean) {
        if (points.isEmpty()) return

        val jointPaint = Paint().apply {
            color = if (isGhost) Color.GRAY else Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            strokeWidth = if (isGhost) 8f else 16f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        for ((startType, endType) in POSE_CONNECTIONS) {
            val start = points[startType] ?: continue
            val end = points[endType] ?: continue

            linePaint.color = if (isGhost) {
                Color.DKGRAY
            } else {
                when (BODY_PARTS[startType to endType] ?: "") {
                    "HEAD" -> Color.MAGENTA
                    "LEFT_ARM", "RIGHT_ARM" -> Color.RED
                    "TRUNK" -> Color.CYAN
                    "LEFT_LEG", "RIGHT_LEG" -> Color.GREEN
                    else -> Color.YELLOW
                }
            }

            canvas.drawLine(start.x, start.y, end.x, end.y, linePaint)
        }

        for ((_, point) in points) {
            canvas.drawCircle(point.x, point.y, if (isGhost) 10f else 20f, jointPaint)
        }
    }

    fun clearPose(isGhost: Boolean = false) {
        if (isGhost) {
            ghostSmoothedPoints.clear()
        } else {
            liveSmoothedPoints.clear()
        }
        invalidate()
    }

    companion object {
        // Note: The indices for the points map correspond to the PoseLandmark types
        val POSE_CONNECTIONS = listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
            PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
            PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
            PoseLandmark.NOSE to PoseLandmark.LEFT_EYE,
            PoseLandmark.NOSE to PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EYE to PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EYE to PoseLandmark.LEFT_EAR,
            PoseLandmark.RIGHT_EYE to PoseLandmark.RIGHT_EAR
        )

        val BODY_PARTS = mapOf(
            (PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER) to "TRUNK",
            (PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP) to "TRUNK",
            (PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP) to "TRUNK",
            (PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP) to "TRUNK",
            (PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW) to "LEFT_ARM",
            (PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST) to "LEFT_ARM",
            (PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW) to "RIGHT_ARM",
            (PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST) to "RIGHT_ARM",
            (PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE) to "LEFT_LEG",
            (PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE) to "LEFT_LEG",
            (PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE) to "RIGHT_LEG",
            (PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE) to "RIGHT_LEG",
            (PoseLandmark.NOSE to PoseLandmark.LEFT_EYE) to "HEAD",
            (PoseLandmark.NOSE to PoseLandmark.RIGHT_EYE) to "HEAD",
            (PoseLandmark.LEFT_EYE to PoseLandmark.RIGHT_EYE) to "HEAD",
            (PoseLandmark.LEFT_EYE to PoseLandmark.LEFT_EAR) to "HEAD",
            (PoseLandmark.RIGHT_EYE to PoseLandmark.RIGHT_EAR) to "HEAD"
        )
    }
}
