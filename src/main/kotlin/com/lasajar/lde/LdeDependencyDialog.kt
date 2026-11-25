package com.lasajar.lde

import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.lasajar.lde.registry.LdeIndexEntry
import com.lasajar.lde.registry.LdeRegistryService
import kotlinx.coroutines.runBlocking
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** Asks for the name and source of a dependency; [existing] prefills the fields when editing. */
class LdeDependencyDialog(
    private val project: Project,
    private val existing: LdeDependency?,
    private val takenNames: Set<String>,
) : DialogWrapper(project) {

    private enum class Source(val label: String) {
        Registry("Registry"), Git("Git"), Local("Local");

        override fun toString() = label
    }

    /** Name of the registry package picked from the search results; prefilled when editing a registry dependency. */
    private var registryName = (existing as? LdeDependency.Registry)?.name

    private val nameField = JBTextField(existing?.takeIf { it !is LdeDependency.Registry }?.name.orEmpty())
    private val versionModel = CollectionComboBoxModel<String>()
    private val versionField = ComboBox(versionModel).apply {
        isEditable = true
        item = (existing as? LdeDependency.Registry)?.version
    }
    private val gitField = JBTextField((existing as? LdeDependency.Git)?.git.orEmpty())
    private val pathField = JBTextField((existing as? LdeDependency.Local)?.path.orEmpty())

    private val searchField = SearchTextField(false)
    private val resultsModel = CollectionListModel<LdeIndexEntry>()
    private val resultsList = JBList(resultsModel)

    /** Name of the package whose README is shown in [readmePane]. */
    private var readmeName: String? = null
    private val readmePane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().build()
        isEditable = false
        border = JBUI.Borders.empty(8)
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
        text = "Select a package to preview its README"
    }

    private val tabs = JBTabbedPane()

    private val source: Source get() = Source.entries[tabs.selectedIndex]

    private lateinit var nameRow: Row

    init {
        title = if (existing == null) "Add Dependency" else "Edit Dependency"
        init()
        loadRegistryIndex()
        registryName?.let { loadVersions(it, versionField.item) }
    }

    override fun createCenterPanel(): JComponent {
        resultsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        resultsList.emptyText.text = "Type to search the lde registry"
        resultsList.cellRenderer = object : ColoredListCellRenderer<LdeIndexEntry>() {
            override fun customizeCellRenderer(
                list: JList<out LdeIndexEntry>, value: LdeIndexEntry, index: Int, selected: Boolean, hasFocus: Boolean,
            ) {
                append(value.name)
                value.latest?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                value.description?.let { append("  $it", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES) }
            }
        }
        resultsList.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val entry = resultsList.selectedValue ?: return@addListSelectionListener
            loadReadme(entry)
            if (entry.name == registryName) return@addListSelectionListener
            registryName = entry.name
            loadVersions(entry.name, entry.latest)
        }
        searchField.text = registryName.orEmpty()
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshResults()
        })

        val registryPanel = panel {
            row { cell(searchField).align(AlignX.FILL) }
            row {
                cell(ScrollPaneFactory.createScrollPane(resultsList).apply { preferredSize = JBUI.size(450, 400) })
                    .align(Align.FILL)
            }.resizableRow()
            row("Version:") { cell(versionField).align(AlignX.FILL) }
        }
        tabs.addTab(Source.Registry.label, JBSplitter(false, 0.45f).apply {
            firstComponent = registryPanel
            secondComponent = ScrollPaneFactory.createScrollPane(readmePane).apply { preferredSize = JBUI.size(550, 400) }
        })
        tabs.addTab(Source.Git.label, panel {
            row("Git URL:") { cell(gitField).align(AlignX.FILL) }
        })
        tabs.addTab(Source.Local.label, panel {
            row("Local path:") { cell(pathField).align(AlignX.FILL) }
        })
        tabs.selectedIndex = when (existing) {
            is LdeDependency.Git -> Source.Git.ordinal
            is LdeDependency.Local -> Source.Local.ordinal
            else -> Source.Registry.ordinal
        }
        tabs.addChangeListener { nameRow.visible(source != Source.Registry) }

        return panel {
            nameRow = row("Name:") { cell(nameField).align(AlignX.FILL) }
            nameRow.visible(source != Source.Registry)
            row { cell(tabs).align(Align.FILL) }.resizableRow()
        }
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (source == Source.Registry) searchField.textEditor else nameField

    override fun doValidate(): ValidationInfo? {
        val name = currentName()
        val nameComponent = if (source == Source.Registry) resultsList else nameField
        when {
            name.isEmpty() && source == Source.Registry -> return ValidationInfo("Select a package from the registry", resultsList)
            name.isEmpty() -> return ValidationInfo("Name is required", nameField)
            name != existing?.name && name in takenNames ->
                return ValidationInfo("Dependency '$name' already exists", nameComponent)
        }
        val text = valueText()
        return if (text.isEmpty()) ValidationInfo("${source.label} value is required", valueComponent()) else null
    }

    private fun currentName(): String = when (source) {
        Source.Registry -> registryName.orEmpty()
        Source.Git, Source.Local -> nameField.text.trim()
    }

    private fun valueComponent(): JComponent = when (source) {
        Source.Registry -> versionField
        Source.Git -> gitField
        Source.Local -> pathField
    }

    private fun valueText(): String = when (source) {
        Source.Registry -> versionField.editor.item?.toString().orEmpty()
        Source.Git -> gitField.text
        Source.Local -> pathField.text
    }.trim()

    private fun refreshResults() {
        val term = searchField.text.trim().ifEmpty { null }
        resultsModel.replaceAll(LdeRegistryService.getInstance(project).query().term(term).get())
        val selected = resultsModel.items.indexOfFirst { it.name == registryName }
        if (selected >= 0) resultsList.selectedIndex = selected
    }

    /** Fills the version dropdown with the versions of package [name], selecting [selected] (or the newest one). */
    private fun loadVersions(name: String, selected: String?) {
        versionModel.removeAll()
        versionField.item = selected
        val service = LdeRegistryService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val versions = runCatching { runBlocking { service.versions(name) } }.getOrElse { emptyList() }
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed || registryName != name) return@invokeLater
                val current = versionField.editor.item?.toString()?.trim().orEmpty()
                versionModel.replaceAll(versions)
                versionField.item = current.ifEmpty { versions.firstOrNull() }
            }, ModalityState.any())
        }
    }

    /** Shows the README of package [entry] in [readmePane], unless it is already shown. */
    private fun loadReadme(entry: LdeIndexEntry) {
        if (entry.name == readmeName) return
        readmeName = entry.name
        readmePane.text = "Loading README…"
        val service = LdeRegistryService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val html = runCatching { runBlocking { service.readme(entry) } }.getOrNull()?.let(::convertMarkdownToHtml)
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed || readmeName != entry.name) return@invokeLater
                readmePane.text = html ?: "No README available for ${entry.name}"
                readmePane.caretPosition = 0
            }, ModalityState.any())
        }
    }

    /** Makes sure the registry index is loaded so searches have something to match against. */
    private fun loadRegistryIndex() {
        val service = LdeRegistryService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { runBlocking { service.index() } }
            ApplicationManager.getApplication().invokeLater({ if (!isDisposed) refreshResults() }, ModalityState.any())
        }
    }

    /** The dependency described by the dialog; only valid after it was closed with OK. */
    val dependency: LdeDependency
        get() {
            val name = currentName()
            val value = valueText()
            return when (source) {
                Source.Registry -> LdeDependency.Registry(name, value)
                Source.Git -> LdeDependency.Git(name, value)
                Source.Local -> LdeDependency.Local(name, value)
            }
        }
}
