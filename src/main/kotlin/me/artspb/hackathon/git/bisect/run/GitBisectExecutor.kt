package me.artspb.hackathon.git.bisect.run

import com.intellij.execution.Executor
import com.intellij.openapi.wm.ToolWindowId
import icons.Icons
import javax.swing.Icon

class GitBisectExecutor : Executor() {

    companion object {
        internal const val ID = "GitBisectExecutor"
    }

    override fun getId(): String = ID

    override fun getToolWindowId(): String = ToolWindowId.RUN

    override fun getIcon(): Icon = Icons.GIT_BISECT_RUN

    override fun getDisabledIcon(): Icon? = null

    override fun getToolWindowIcon(): Icon = Icons.GIT_BISECT_RUN

    override fun getContextActionId(): String = "GitBisectRun"

    override fun getActionName(): String = "Git Bisect Run"

    override fun getStartActionText() = "Git Bisect Run"

    override fun getDescription(): String = "Git bisect run selected configuration"

    override fun getHelpId(): String? = null
}
