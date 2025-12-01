package com.example.teamfabiano

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.teamfabiano.CameraFragment.SavedLandmark
import com.google.mlkit.vision.pose.PoseLandmark

class PoseOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val liveSmoothedPoints = mutableMapOf<Int, PointF>()
    private val ghostSmoothedPoints = mutableMapOf<Int, PointF>()
    private var imageWidth = 1
    private var imageHeight = 1
    private var viewWidth = 1
    private var viewHeight = 1
    private val alpha = 0.3f

    // --- Paint Objects ---
    private val ghostPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val livePaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val limbPaint = Paint().apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

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

    fun updateGhostPose(landmarks: List<SavedLandmark>, width: Int, height: Int) {
        updateSmoothedPoints(
            landmarks.map { PointF(it.x, it.y) },
            ghostSmoothedPoints,
            null,
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
        drawHumanoid(canvas, ghostSmoothedPoints, true)
        drawHumanoid(canvas, liveSmoothedPoints, false)
    }

    private fun drawHumanoid(canvas: Canvas, points: Map<Int, PointF>, isGhost: Boolean) {
        if (points.isEmpty()) return

        val paint = if (isGhost) ghostPaint else livePaint
        val limbWidth = if (isGhost) 15f else 30f
        limbPaint.strokeWidth = limbWidth

        // Draw Limbs
        for ((startType, endType) in LIMB_CONNECTIONS) {
            val start = points[startType] ?: continue
            val end = points[endType] ?: continue
            limbPaint.color = paint.color
            canvas.drawLine(start.x, start.y, end.x, end.y, limbPaint)
        }

        // Draw Torso
        val torsoPath = Path()
        val leftShoulder = points[PoseLandmark.LEFT_SHOULDER]
        val rightShoulder = points[PoseLandmark.RIGHT_SHOULDER]
        val leftHip = points[PoseLandmark.LEFT_HIP]
        val rightHip = points[PoseLandmark.RIGHT_HIP]

        if (leftShoulder != null && rightShoulder != null && leftHip != null && rightHip != null) {
            torsoPath.moveTo(leftShoulder.x, leftShoulder.y)
            torsoPath.lineTo(rightShoulder.x, rightShoulder.y)
            torsoPath.lineTo(rightHip.x, rightHip.y)
            torsoPath.lineTo(leftHip.x, leftHip.y)
            torsoPath.close()
            canvas.drawPath(torsoPath, paint)
        }

        // Draw Head
        points[PoseLandmark.NOSE]?.let {
            val headRadius = limbWidth * 1.5f
            canvas.drawCircle(it.x, it.y, headRadius, paint)
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
        val LIMB_CONNECTIONS = listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
            PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
            PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE
        )
    }
}
