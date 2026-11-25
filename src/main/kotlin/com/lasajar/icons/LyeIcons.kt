package com.lasajar.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.LayeredIcon
import com.intellij.util.IconUtil
import javax.swing.Icon
import javax.swing.SwingConstants

object LyeIcons {
    @JvmField
    val Lua = IconLoader.getIcon("/icons/lua.svg", LyeIcons::class.java)
    @JvmField
    val Lde = IconLoader.getIcon("/icons/lde.svg", LyeIcons::class.java)
    @JvmField
    val LdePackage: Icon = LayeredIcon(2).apply {
        setIcon(AllIcons.Nodes.Folder, 0)
        setIcon(IconUtil.scale(Lde, null, 0.75f), 1, SwingConstants.SOUTH_EAST)
    }

    @JvmField
    val LuaTest: Icon = LayeredIcon.layeredIcon(arrayOf(Lua, AllIcons.Nodes.JunitTestMark))
    @JvmField
    val LdeBuild: Icon = LayeredIcon.layeredIcon(arrayOf(Lua, AllIcons.Nodes.ProcessMark))
    @JvmField
    val LdeJson: Icon = LayeredIcon(2).apply {
        setIcon(Lde, 0)
        setIcon(IconUtil.scale(AllIcons.General.Settings, null, 0.5f), 1, SwingConstants.NORTH_EAST)
    }
}
