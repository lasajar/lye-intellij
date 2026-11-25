package com.lasajar.lde.registry

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
internal class LdeRegistryService(private val project: Project) {
    val registry = LdeRegistry()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            runCatching { registry.index() }
                .onFailure { logger<LdeRegistryService>().warn("Failed to warm up lde registry", it) }
        }
    }

    suspend fun index(forceRefresh: Boolean = false): List<LdeIndexEntry> = registry.index(forceRefresh)

    suspend fun versions(name: String): List<String> = registry.versions(name)

    suspend fun readme(entry: LdeIndexEntry): String? = registry.readme(entry)

    suspend fun clearCache() = registry.clearCache()

    fun query(): LdeRegistry.Query = registry.query()

    companion object {
        fun getInstance(project: Project): LdeRegistryService = project.service()
    }
}