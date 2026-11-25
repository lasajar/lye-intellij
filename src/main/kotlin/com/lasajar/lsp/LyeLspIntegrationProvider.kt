package com.lasajar.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.ensureClientStarted
import java.nio.file.Path

class LyeLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter,
    ) {
        if (file.extension != "lua") {
            return;
        }

        val binary = LyeBinary()

        binary.cachedOrNull()?.let {
            clientStarter.ensureClientStarted(LyeLspClientDescriptor(project, it))
            if (binary.isUpdateCheckDue()) {
                object : Task.Backgroundable(project, "Checking for lye updates", true) {
                    override fun run(indicator: ProgressIndicator) {
                        binary.checkForUpdate(indicator)
                    }
                }.queue()
            }
            return
        }

        object : Task.Backgroundable(project, "Downloading lye", true) {
            private lateinit var path: Path

            override fun run(indicator: ProgressIndicator) {
                path = binary.getOrDownload(indicator)
            }

            override fun onSuccess() {
                LspClientManager.getInstance(project)
                    .ensureClientStarted<LyeLspIntegrationProvider>(LyeLspClientDescriptor(project, path))
            }
        }.queue()
    }
}

private class LyeLspClientDescriptor(project: Project, private val binary: Path) : ProjectWideLspClientDescriptor(project, "Lye") {
    override fun isSupportedFile(file: VirtualFile) = file.extension == "lua"

    override fun createCommandLine() = GeneralCommandLine(binary.toString() ?: "lye", "--stdio")
}
