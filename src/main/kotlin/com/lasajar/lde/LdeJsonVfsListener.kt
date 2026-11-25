package com.lasajar.lde

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class LdeJsonVfsListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<out VFileEvent>) {
        if (events.any { it.path.endsWith("lde.json") }) {
            LdePackageService.getInstance(project).rescan()
        }
    }
}