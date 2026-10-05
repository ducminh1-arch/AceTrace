package com.acetrace.app.core.storage

import android.content.Context
import com.acetrace.app.core.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ProjectStore(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val projectsDir: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    suspend fun saveProject(project: Project): File = withContext(Dispatchers.IO) {
        val file = File(projectsDir, "${project.id}.json")
        val jsonString = json.encodeToString(project)
        file.writeText(jsonString)
        file
    }

    suspend fun loadProject(projectId: String): Project? = withContext(Dispatchers.IO) {
        val file = File(projectsDir, "$projectId.json")
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString<Project>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    suspend fun listProjects(): List<Project> = withContext(Dispatchers.IO) {
        val files = projectsDir.listFiles { _, name -> name.endsWith(".json") } ?: return@withContext emptyList()
        files.mapNotNull { file ->
            try {
                json.decodeFromString<Project>(file.readText())
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.createdAt }
    }
}
