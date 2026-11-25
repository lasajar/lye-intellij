package com.lasajar.lde

import com.lasajar.icons.LyeIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleTextAttributes

class LdePackageDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val dir = node.virtualFile?.takeIf { it.isDirectory } ?: return

        if (!isLdePackage(dir)) {
            return;
        }

        data.setIcon(LyeIcons.LdePackage)
        data.clearText()
        data.addText(dir.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
    }

    private fun isLdePackage(dir: VirtualFile): Boolean {
        return dir.findChild("lde.json")?.isDirectory == false
    }
}
