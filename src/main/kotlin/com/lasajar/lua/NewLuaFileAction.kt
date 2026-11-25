package com.lasajar.lua

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.lasajar.icons.LyeIcons
import org.jetbrains.annotations.NonNls

class NewLuaFileAction : CreateFileFromTemplateAction("Lua File", "Create a new Lua file", LyeIcons.Lua), DumbAware {
    override fun buildDialog(proj: Project, dir: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Lua File")
            .addKind("Lua file", LyeIcons.Lua, LUA_FILE)
            .addKind("Lua test file", LyeIcons.LuaTest, LUA_TEST)
    }

    override fun createFile(name: String, templateName: String, dir: PsiDirectory?): PsiFile? {
        val base = name.removeSuffix(".lua")
        val fileName = if (templateName == LUA_TEST && !base.endsWith(".test")) "$base.test" else base
        return super.createFile(fileName, templateName, dir)
    }

    override fun getActionName(
        dir: PsiDirectory?,
        newName: @NonNls String,
        templateName: @NonNls String?
    ): @NlsContexts.Command String? = "Create Lua File $newName"

    companion object {
        const val LUA_FILE = "Lua File"
        const val LUA_TEST = "Lua Test"
    }
}