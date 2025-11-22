package com.example.teamfabiano

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MainMenuFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_main_menu, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val userStatus = UserStatus.load(requireContext().filesDir)
        val levels = loadLevels()
        val lessons = loadLessons()

        val beltImageView = view.findViewById<ImageView>(R.id.image_belt)
        val rankTextView = view.findViewById<TextView>(R.id.text_rank)
        val xpProgressBar = view.findViewById<ProgressBar>(R.id.progress_xp)
        val xpTextView = view.findViewById<TextView>(R.id.text_xp)
        val lessonsContainer = view.findViewById<LinearLayout>(R.id.lessons_container)

        // --- Level Indicator Logic ---
        rankTextView.text = userStatus.rank

        val currentLevelIndex = levels.indexOfFirst { it.rank == userStatus.rank }
        val currentLevel = levels.getOrNull(currentLevelIndex)
        val nextLevel = levels.getOrNull(currentLevelIndex + 1)

        if (currentLevel != null && nextLevel != null) {
            val xpForCurrentLevel = currentLevel.xp_required
            val xpForNextLevel = nextLevel.xp_required
            val progress = userStatus.xp - xpForCurrentLevel
            val maxProgress = xpForNextLevel - xpForCurrentLevel

            xpProgressBar.max = maxProgress
            xpProgressBar.progress = progress
            xpTextView.text = "${userStatus.xp} / $xpForNextLevel XP"
            xpProgressBar.visibility = View.VISIBLE
            xpTextView.visibility = View.VISIBLE
        } else {
            // Max level reached
            xpProgressBar.visibility = View.GONE
            xpTextView.text = "Max Rank"
        }

        // --- Belt Image Logic ---
        val beltDrawable = when (userStatus.rank) {
            "White Pra Jiad" -> R.drawable.belt_white
            "Yellow Pra Jiad" -> R.drawable.belt_yellow
            "Blue Pra Jiad" -> R.drawable.belt_blue
            else -> R.drawable.belt_white
        }
        beltImageView.setImageResource(beltDrawable)

        // --- Unlocked Lessons Logic ---
        lessonsContainer.removeAllViews() // Clear previous items
        currentLevel?.lessons?.forEach { lessonId ->
            val lesson = lessons.find { it.id == lessonId }
            if (lesson != null) {
                val inflater = LayoutInflater.from(requireContext())
                val lessonView = inflater.inflate(R.layout.list_item_lesson, lessonsContainer, false) as CardView

                val titleTextView = lessonView.findViewById<TextView>(R.id.text_lesson_title)
                titleTextView.text = lesson.title

                lessonView.setOnClickListener {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, LessonVideoFragment.newInstance(lessonId))
                        .addToBackStack(null)
                        .commit()
                }
                lessonsContainer.addView(lessonView)
            }
        }

        view.findViewById<Button>(R.id.button_record_lesson).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, RecordingFragment())
                .addToBackStack(null)
                .commit()
        }
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
}
