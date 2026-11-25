package com.lasajar.lde

import com.intellij.ide.IdeView
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiDirectory
import com.lasajar.icons.LyeIcons

class NewLdeJsonAction : AnAction("lde.json", "Create a new lde.json file", LyeIcons.LdeJson), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val dir = targetDirectory(e)
        e.presentation.isVisible = dir != null
        e.presentation.isEnabled = dir != null && dir.findFile(LDE_JSON) == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val view = e.getData(LangDataKeys.IDE_VIEW) ?: return
        val dir = targetDirectory(e) ?: return
        if (dir.findFile(LDE_JSON) != null) return

        val template = FileTemplateManager.getInstance(dir.project).getInternalTemplate(LDE_TEMPLATE)
        val created = FileTemplateUtil.createFromTemplate(template, LDE_JSON, null, dir)
        view.selectElement(created)
    }

    private fun targetDirectory(e: AnActionEvent): PsiDirectory? {
        val view: IdeView = e.getData(LangDataKeys.IDE_VIEW) ?: return null
        return view.directories.singleOrNull()
    }

    companion object {
        const val LDE_JSON = "lde.json"
        const val LDE_TEMPLATE = "lde"
    }
}
