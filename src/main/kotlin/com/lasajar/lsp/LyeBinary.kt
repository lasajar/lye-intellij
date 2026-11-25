package com.lasajar.lsp

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.io.HttpRequests
import com.intellij.util.system.CpuArch
import org.jetbrains.builtInWebServer.getBuiltInServerUrls
import java.io.IOException
import java.nio.file.Path
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class LyeBinary {
    private fun getBinaryName() = when {
        SystemInfo.isWindows && CpuArch.isArm64() -> "lye-windows-aarch64.exe"
        SystemInfo.isWindows -> "lye-windows-x86-64.exe"
        SystemInfo.isMac && CpuArch.isArm64() -> "lye-macos-aarch64"
        SystemInfo.isMac -> "lye-macos-x86-64"
        SystemInfo.isLinux && CpuArch.isArm64() -> "lye-linux-aarch64"
        SystemInfo.isLinux -> "lye-linux-x86-64"
        else -> error("Unsupported platform")
    }

    private val releaseUrl get() = "$LYE_RELEASE_URL/${getBinaryName()}"

    private val cachedPath: Path = PathManager.getSystemDir().resolve("lye").resolve(getBinaryName())

    private val pendingPath: Path = cachedPath.resolveSibling("${getBinaryName()}.new")

    private val properties get() = PropertiesComponent.getInstance()

    fun cachedOrNull(): Path? {
        synchronized(DOWNLOAD_LOCK) { applyPendingUpdate() }
        return cachedPath.toFile().takeIf { it.exists() }?.toPath()
    }

    /**
     * DO NOT CALL ON EDT!
     */
    fun getOrDownload(indicator: ProgressIndicator): Path = synchronized(DOWNLOAD_LOCK) {
        val target = cachedPath
        if (Files.isRegularFile(target)) { return target }
        download(target, indicator)
        target
    }

    fun isUpdateCheckDue() =
        System.currentTimeMillis() - properties.getLong(LAST_CHECK_KEY, 0L) >= UPDATE_CHECK_INTERVAL_MS

    /**
     * Checks whether the nightly binary changed upstream and, if so, downloads it next to the cached one.
     * The new binary replaces the cached one the next time [cachedOrNull] is called while it isn't in use.
     * Returns true if an update was downloaded.
     *
     * DO NOT CALL ON EDT!
     */
    fun checkForUpdate(indicator: ProgressIndicator): Boolean = synchronized(DOWNLOAD_LOCK) {
        properties.setValue(LAST_CHECK_KEY, System.currentTimeMillis().toString())
        val remoteEtag = HttpRequests.head(releaseUrl).connect { it.connection.getHeaderField("ETag") }
            ?: return false
        if (remoteEtag == properties.getValue(ETAG_KEY)) return false
        download(pendingPath, indicator)
        true
    }

    private fun applyPendingUpdate() {
        if (!Files.isRegularFile(pendingPath)) return
        try {
            Files.move(pendingPath, cachedPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // Cached binary is still in use (e.g. locked on Windows); retry next time.
        }
    }

    private fun download(target: Path, indicator: ProgressIndicator) {
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, getBinaryName(), ".part")
        try {
            val etag = HttpRequests.request(releaseUrl).connect {
                it.saveToFile(temp.toFile(), indicator)
                it.connection.getHeaderField("ETag")
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            if (!SystemInfo.isWindows) target.toFile().setExecutable(true)
            properties.setValue(ETAG_KEY, etag)
            properties.setValue(LAST_CHECK_KEY, System.currentTimeMillis().toString())
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val LYE_RELEASE_URL = "https://github.com/lde-org/lye/releases/download/nightly"
        private val DOWNLOAD_LOCK = Any()
        private const val ETAG_KEY = "com.lasajar.lye.binary.etag"
        private const val LAST_CHECK_KEY = "com.lasajar.lye.binary.lastUpdateCheck"
        private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}