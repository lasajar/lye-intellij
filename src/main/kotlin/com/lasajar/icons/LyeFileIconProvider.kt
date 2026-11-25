package com.lasajar.icons

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import javax.swing.Icon

class LyeFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (file.isDirectory) return null
        val name = file.name
        return when {
            name == "lde.json" -> LyeIcons.LdeJson
            name.endsWith(".test.lua") -> LyeIcons.LuaTest
            name == "build.lua" && file.parent?.findChild("lde.json")?.isFile == true -> LyeIcons.LdeBuild
            else -> null
        }
    }
}
