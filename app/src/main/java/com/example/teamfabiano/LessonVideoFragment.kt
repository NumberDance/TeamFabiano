package com.example.teamfabiano

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class LessonVideoFragment : Fragment() {

    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var lessonId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            lessonId = it.getInt(ARG_LESSON_ID)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_lesson_video, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        playerView = view.findViewById(R.id.player_view)

        val trainingButton = view.findViewById<Button>(R.id.button_start_training)
        trainingButton.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, CameraFragment.newInstance(lessonId))
                .addToBackStack(null)
                .commit()
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun initializePlayer() {
        val lessons = loadLessons()
        val lesson = lessons.find { it.id == lessonId }
        if (lesson == null) {
            Log.e("LessonVideoFragment", "Lesson with ID $lessonId not found.")
            return
        }

        exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
            playerView.player = this

            val videoPath = "android.resource://${requireContext().packageName}/raw/${lesson.video_file.substringBefore(".")}"
            val mediaItem = MediaItem.fromUri(Uri.parse(videoPath))
            setMediaItem(mediaItem)

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val stateString = when (playbackState) {
                        ExoPlayer.STATE_IDLE -> "STATE_IDLE"
                        ExoPlayer.STATE_BUFFERING -> "STATE_BUFFERING"
                        ExoPlayer.STATE_READY -> "STATE_READY"
                        ExoPlayer.STATE_ENDED -> "STATE_ENDED"
                        else -> "UNKNOWN_STATE"
                    }
                    Log.d("LessonVideoFragment", "ExoPlayer state changed to $stateString")
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("LessonVideoFragment", "ExoPlayer error", error)
                }
            })

            prepare()
            playWhenReady = true
        }
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }
    
    private fun loadLessons(): List<Lesson> {
        return try {
            val json = requireContext().assets.open("lessons.json").bufferedReader().use { it.readText() }
            val type = object : TypeToken<Map<String, List<Lesson>>>() {}.type
            val data: Map<String, List<Lesson>> = Gson().fromJson(json, type)
            data["lessons"] ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val ARG_LESSON_ID = "lesson_id"

        fun newInstance(lessonId: Int) = LessonVideoFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_LESSON_ID, lessonId)
            }
        }
    }
}
