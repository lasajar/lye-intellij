package com.lasajar.lde

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.execution.ParametersListUtil
import com.lasajar.icons.LyeIcons
import javax.swing.JTextField

class LdeConfigurationType : SimpleConfigurationType(
    "LdeRunConfiguration", "lde", "Run Lua with lde",
    NotNullLazyValue.createValue { LyeIcons.Lde }
) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration = LdeRunConfiguration(project, this)

    override fun getOptionsClass() = LdeRunConfigurationOptions::class.java
}

class LdeRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var packagePath by string()
    var arguments by string()
}

class LdeRunConfiguration(project: Project, factory: ConfigurationFactory) :
        LocatableConfigurationBase<LdeRunConfigurationOptions>(project, factory, "LDE") {

    public override fun getOptions() = super.getOptions() as LdeRunConfigurationOptions

    override fun getConfigurationEditor() = LdeSettingsEditor(project)

    override fun checkConfiguration() {
       if (options.packagePath.isNullOrBlank())
           throw RuntimeConfigurationError("Package path cannot be blank")
    }

    override fun getState(executor: Executor, env: ExecutionEnvironment) = object : CommandLineState(env) {
        override fun startProcess() = KillableColoredProcessHandler(
            GeneralCommandLine("lde", "run")
                .withWorkDirectory(options.packagePath!!)
                .withParameters(ParametersListUtil.parse(options.arguments.orEmpty()))
        ).also { ProcessTerminatedListener.attach(it) }
    }
}

class LdeSettingsEditor(private val project: Project) : SettingsEditor<LdeRunConfiguration>() {
    private val packageDir = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor())
    }

    private val args = JTextField()

    override fun resetEditorFrom(c: LdeRunConfiguration) {
        packageDir.text = c.options.packagePath.orEmpty()
        args.text = c.options.arguments.orEmpty()
    }

    override fun applyEditorTo(p0: LdeRunConfiguration) {
        (p0.options as LdeRunConfigurationOptions).apply {
            packagePath = packageDir.text
            arguments = args.text
        }
    }

    override fun createEditor() = panel {
        row("Package path:") { cell(packageDir).align(AlignX.FILL) }
        row("Arguments:") { cell(args).align(AlignX.FILL) }
    }
}

class LdeRunConfigurationProducer : LazyRunConfigurationProducer<LdeRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = ConfigurationTypeUtil.findConfigurationType(
        LdeConfigurationType::class.java)

    override fun setupConfigurationFromContext(
        p0: LdeRunConfiguration,
        p1: ConfigurationContext,
        p2: Ref<PsiElement?>
    ): Boolean {
        val file = p1.location?.virtualFile ?: return false
        val dir = if (file.isDirectory) file else file.parent ?: return false
        (p0.options as LdeRunConfigurationOptions).packagePath = dir.path
        p0.name = "LDE: ${dir.name}"
        p2.set(p1.psiLocation)
        return true
    }

    override fun isConfigurationFromContext(c: LdeRunConfiguration, ctx: ConfigurationContext): Boolean = c.options.packagePath == ctx.location?.virtualFile?.path
}