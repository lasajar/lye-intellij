package com.lasajar.lde

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.ModalTaskOwner.project

class LdeRunConfigurationSync(private val project: Project) : LdePackageListener {
    override fun packagesChanged(packages: List<LdePackage>) {
        val runManager = RunManager.getInstance(project)
        val type = ConfigurationTypeUtil.findConfigurationType(LdeConfigurationType::class.java)
        val existing = runManager.getConfigurationsList(type).map { it.name }.toSet()

        for (pkg in packages) {
            if (pkg.name in existing || pkg.bin.isNullOrEmpty()) {
                continue
            }

            val settings = runManager.createConfiguration(pkg.name, type.configurationFactories.single())
            (settings.configuration as LdeRunConfiguration).options.packagePath = pkg.dir.path
            runManager.addConfiguration(settings)
        }
    }
}