package icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.LayeredIcon.Companion.layeredIcon
import javax.swing.Icon

@Suppress("unused")
object Icons {
    @JvmField val GIT: Icon = IconLoader.getIcon("/icons/git.png", Icons.javaClass)
    @JvmField val GIT_BISECT_RUN: Icon = layeredIcon(arrayOf(GIT, AllIcons.RunConfigurations.TestState.Run))
}
