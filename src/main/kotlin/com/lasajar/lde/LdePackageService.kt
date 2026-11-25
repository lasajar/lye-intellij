package com.lasajar.lde

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LdePackage(val name: String, val file: VirtualFile, val bin: String?, val dependencies: List<LdeDependency>) {
    val dir: VirtualFile get() = file.parent
}

sealed interface LdeDependency {
    val name: String
    val isRemote: Boolean

    data class Registry(override val name: String, val version: String) : LdeDependency {
        override val isRemote get() = true
    }

    data class Git(override val name: String, val git: String) : LdeDependency {
        override val isRemote get() = true
    }

    data class Local(override val name: String, val path: String) : LdeDependency {
        override val isRemote get() = false
    }
}

@Service(Service.Level.PROJECT)
internal class LdePackageService(private val project: Project, private val cs: CoroutineScope) {
    @Volatile var packages: List<LdePackage> = emptyList()
        private set

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun rescan() = cs.launch {
        DumbService.getInstance(project).waitForSmartMode()
        packages = readAction {
            FilenameIndex.getVirtualFilesByName("lde.json", GlobalSearchScope.projectScope(project))
                .mapNotNull(::parse)
        }
        project.messageBus.syncPublisher(LdePackageListener.TOPIC).packagesChanged(packages)
    }

    private fun parse(file: VirtualFile): LdePackage? {
        val json = runCatching { JsonParser.parseString(VfsUtilCore.loadText(file)).asJsonObject }.getOrNull() ?: return null
        return LdePackage(
            json.get("name")?.asString ?: return null,
            file,
            json.get("bin")?.asString,
            parseDependencies(json.get("dependencies")),
        )
    }

    private fun parseDependencies(element: JsonElement?): List<LdeDependency> {
        if (element == null || !element.isJsonObject) return emptyList()
        return element.asJsonObject.entrySet().mapNotNull { (name, value) ->
            if (!value.isJsonObject) return@mapNotNull null
            val dep = value.asJsonObject
            dep.get("version")?.asString?.let { return@mapNotNull LdeDependency.Registry(name, it) }
            dep.get("git")?.asString?.let { return@mapNotNull LdeDependency.Git(name, it) }
            dep.get("path")?.asString?.let { return@mapNotNull LdeDependency.Local(name, it) }
            null
        }
    }

    fun update(pkg: LdePackage, transform: (LdePackage) -> LdePackage) = cs.launch {
        val new = transform(pkg)
        withContext(Dispatchers.EDT) {
            val document = FileDocumentManager.getInstance().getDocument(pkg.file) ?: return@withContext
            val json = runCatching { JsonParser.parseString(document.text).asJsonObject }.getOrNull() ?: return@withContext

            write(json, new)
            WriteCommandAction.runWriteCommandAction(project, "Edit ${pkg.name}", null, {
                document.setText(gson.toJson(json))
                FileDocumentManager.getInstance().saveDocument(document)
            })

            rescan()
        }
    }

    private fun write(json: JsonObject, pkg: LdePackage): JsonObject {
        json.addProperty("name", pkg.name)
        if (pkg.bin != null) json.addProperty("bin", pkg.bin) else json.remove("bin")
        val existing = json.get("dependencies")?.takeIf { it.isJsonObject }?.asJsonObject
        val deps = JsonObject()
        for (dep in pkg.dependencies) {
            val entry = existing?.get(dep.name)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            listOf("version", "git", "path").forEach(entry::remove)
            when (dep) {
                is LdeDependency.Registry -> entry.addProperty("version", dep.version)
                is LdeDependency.Git -> entry.addProperty("git", dep.git)
                is LdeDependency.Local -> entry.addProperty("path", dep.path)
            }
            deps.add(dep.name, entry)
        }
        json.add("dependencies", deps)
        return json
    }

    companion object {
        fun getInstance(project: Project): LdePackageService = project.service()
    }
}

fun interface LdePackageListener {
    fun packagesChanged(packages: List<LdePackage>)
    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic(LdePackageListener::class.java)
    }
}

class LdePackageStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        LdePackageService.getInstance(project).rescan()
    }
}