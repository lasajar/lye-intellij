package com.lasajar.lua

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.lasajar.icons.LyeIcons
import javax.swing.Icon

object LuaLanguage : Language("Lua") {
    private fun readResolve(): Any = LuaLanguage
}

object LuaFileType : LanguageFileType(LuaLanguage) {
    override fun getName() = "Lua"

    override fun getDescription() = "Lua"

    override fun getDefaultExtension() = "lua"

    override fun getIcon(): Icon = LyeIcons.Lua
}
