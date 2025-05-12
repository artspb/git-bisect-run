package me.artspb.hackathon.git.bisect.run.action

import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import me.artspb.hackathon.git.bisect.run.GitBisectRunState
import me.artspb.hackathon.git.bisect.run.isRunnable

sealed class GitBisectBehaviorAction(private val behavior: GitBisectRunState.Behavior) : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isVisible = GitBisectRunState.get(project)?.isInProgress == true
        e.presentation.isEnabled = RunManager.getInstance(project).selectedConfiguration.isRunnable()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val state = GitBisectRunState.get(e.project) ?: return
        behavior.action(state, "")
    }
}

class GitBisectGoodAction : GitBisectBehaviorAction(GitBisectRunState.Behavior.GOOD)
class GitBisectBadAction : GitBisectBehaviorAction(GitBisectRunState.Behavior.BAD)
class GitBisectSkipAction : GitBisectBehaviorAction(GitBisectRunState.Behavior.SKIP)
