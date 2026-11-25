package com.lasajar.lde

import com.intellij.ide.projectView.PresentationData
import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.SimpleNode
import com.intellij.ui.treeStructure.SimpleTreeStructure
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import com.lasajar.icons.LyeIcons
import java.awt.event.MouseEvent

class LdeProjectsView(private val project: Project, parentDisposable: Disposable) :
    SimpleToolWindowPanel(true, true) {

    private val structureModel = StructureTreeModel(LdeTreeStructure(project), parentDisposable)
    private val tree = Tree(AsyncTreeModel(structureModel, parentDisposable)).apply {
        isRootVisible = true
    }

    init {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val node = TreeUtil.getLastUserObject(LdeNode::class.java, tree.selectionPath) ?: return false
                if (node is LdeDependencyNode) editSelectedDependency() else node.activate()
                return true
            }
        }.installOn(tree)

        project.messageBus.connect(parentDisposable).subscribe(LdePackageListener.TOPIC, LdePackageListener {
            structureModel.invalidateAsync()
        })

        val expander = DefaultTreeExpander(tree)
        val actions = CommonActionsManager.getInstance()
        val group = DefaultActionGroup().apply {
            add(AddDependencyAction())
            addSeparator()
            add(actions.createExpandAllAction(expander, tree))
            add(actions.createCollapseAllAction(expander, tree))
        }
        PopupHandler.installPopupMenu(
            tree,
            DefaultActionGroup(AddDependencyAction(), EditDependencyAction(), RemoveDependencyAction()),
            "LdeProjectsViewPopup",
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("LdeProjectsView", group, true)
        toolbar.targetComponent = tree

        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(tree))
    }

    private fun selectedNode(): LdeNode? = TreeUtil.getLastUserObject(LdeNode::class.java, tree.selectionPath)

    /** The package of the selected node: the package itself, or the package owning a selected dependency. */
    private fun selectedPackage(): LdePackage? = when (val node = selectedNode()) {
        is LdePackageNode -> node.pkg
        is LdeDependencyNode -> (node.parent as? LdePackageNode)?.pkg
        else -> null
    }

    private inner class AddDependencyAction :
        AnAction("Add Dependency", "Add a dependency to the selected package", AllIcons.General.Add) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val pkg = selectedPackage()
            if (e.isFromContextMenu) e.presentation.isEnabledAndVisible = selectedNode() is LdePackageNode
            else e.presentation.isEnabled = pkg != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val pkg = selectedPackage() ?: return
            val dialog = LdeDependencyDialog(project, null, pkg.dependencies.map { it.name }.toSet())
            if (!dialog.showAndGet()) return
            val dependency = dialog.dependency
            LdePackageService.getInstance(project).update(pkg) { it.copy(dependencies = it.dependencies + dependency) }
        }
    }

    private inner class EditDependencyAction :
        AnAction("Edit Dependency…", "Edit the selected dependency", AllIcons.Actions.Edit) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = selectedNode() is LdeDependencyNode && selectedPackage() != null
        }

        override fun actionPerformed(e: AnActionEvent) = editSelectedDependency()
    }

    private fun editSelectedDependency() {
        val old = (selectedNode() as? LdeDependencyNode)?.dependency ?: return
        val pkg = selectedPackage() ?: return
        val dialog = LdeDependencyDialog(project, old, pkg.dependencies.map { it.name }.toSet())
        if (!dialog.showAndGet()) return
        val new = dialog.dependency
        LdePackageService.getInstance(project).update(pkg) {
            it.copy(dependencies = it.dependencies.map { dep -> if (dep.name == old.name) new else dep })
        }
    }

    private inner class RemoveDependencyAction :
        AnAction("Remove Dependency", "Remove the selected dependency", AllIcons.General.Remove) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = selectedNode() is LdeDependencyNode && selectedPackage() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val old = (selectedNode() as? LdeDependencyNode)?.dependency ?: return
            val pkg = selectedPackage() ?: return
            val answer = Messages.showYesNoDialog(
                project,
                "Remove dependency '${old.name}' from ${pkg.name}?",
                "Remove Dependency",
                Messages.getQuestionIcon(),
            )
            if (answer != Messages.YES) return
            LdePackageService.getInstance(project).update(pkg) {
                it.copy(dependencies = it.dependencies.filter { dep -> dep.name != old.name })
            }
        }
    }
}

private class LdeTreeStructure(project: Project) : SimpleTreeStructure() {
    private val root = LdeProjectNode(project)

    override fun getRootElement(): Any = root
}

abstract class LdeNode(project: Project, parent: SimpleNode?) : SimpleNode(project, parent) {
    /** Called when the node is double-clicked. */

    open fun activate() {}
}

class LdeProjectNode(project: Project) : LdeNode(project, null) {
    init {
        icon = AllIcons.Nodes.Module
    }

    override fun getName(): String = myProject.name

    override fun getChildren(): Array<SimpleNode> {
        return LdePackageService.getInstance(myProject).packages
            .map { LdePackageNode(myProject, this, it) }
            .toTypedArray()
    }
}

class LdePackageNode(project: Project, parent: LdeNode, val pkg: LdePackage) : LdeNode(project, parent) {
    init {
        icon = LyeIcons.LdePackage
    }

    override fun getName(): String = pkg.name

    override fun update(presentation: PresentationData) {
        super.update(presentation)
        presentation.addText(pkg.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
    }

    override fun getChildren(): Array<SimpleNode> {
        return pkg.dependencies
            .sortedBy { it.name }
            .map { LdeDependencyNode(myProject, this, it) }
            .toTypedArray()
    }
}

class LdeDependencyNode(project: Project, parent: LdeNode, val dependency: LdeDependency) : LdeNode(project, parent) {
    init {
        icon = if (dependency.isRemote) AllIcons.Nodes.PpLib else AllIcons.Nodes.Module
    }

    override fun getName(): String = dependency.name

    override fun update(presentation: PresentationData) {
        super.update(presentation)
        presentation.locationString = when (dependency) {
            is LdeDependency.Registry -> dependency.version
            is LdeDependency.Git -> dependency.git
            is LdeDependency.Local -> dependency.path
        }
    }

    override fun getChildren(): Array<SimpleNode> = NO_CHILDREN

    override fun isAlwaysLeaf(): Boolean = true
}
