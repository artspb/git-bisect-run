package me.artspb.hackathon.git.bisect.run.action

import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.vcs.log.VcsLogDataKeys
import me.artspb.hackathon.git.bisect.run.GitBisectRunState
import me.artspb.hackathon.git.bisect.run.canRun
import me.artspb.hackathon.git.bisect.run.isRunnable

class GitBisectRunInLogAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = isEnabled(e)
    }

    private fun isEnabled(e: AnActionEvent): Boolean {
        val project = e.project
        if (!project.canRun()) return false
        if (!RunManager.getInstance(project).selectedConfiguration.isRunnable()) return false
        val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION) ?: return false
        val commits = selection.commits
        return commits.size > 1 && commits.first().root == commits.last().root
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (project.isDisposed) return
        val commits = e.getRequiredData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION).commits
        val file = commits.firstOrNull()?.root ?: e.getData(CommonDataKeys.VIRTUAL_FILE)
        val bad = commits.firstOrNull()?.hash?.asString()
        val good = commits.lastOrNull()?.hash?.asString()
        GitBisectRunState.start(project, e.dataContext, file, bad, good)
    }
}
