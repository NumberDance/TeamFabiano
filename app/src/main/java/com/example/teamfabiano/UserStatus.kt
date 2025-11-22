package com.example.teamfabiano

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class Lesson(val id: Int, val title: String, val description: String, val video_file: String, val master_recording_file: String)

data class Level(val rank: String, val xp_required: Int, val lessons: List<Int>)

data class UserStatus(
    var rank: String = "White Pra Jiad",
    var xp: Int = 0
) {
    fun addXp(points: Int, levels: List<Level>) {
        xp += points
        
        val currentLevelIndex = levels.indexOfFirst { it.rank == rank }
        val nextLevel = levels.getOrNull(currentLevelIndex + 1)

        if (nextLevel != null && xp >= nextLevel.xp_required) {
            rank = nextLevel.rank
        }
    }

    companion object {
        fun load(filesDir: File): UserStatus {
            val file = File(filesDir, "user_status.json")
            if (!file.exists()) return UserStatus()
            return try {
                Gson().fromJson(file.readText(), UserStatus::class.java)
            } catch (e: Exception) {
                UserStatus()
            }
        }

        fun save(filesDir: File, status: UserStatus) {
            try {
                File(filesDir, "user_status.json").writeText(Gson().toJson(status))
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
}