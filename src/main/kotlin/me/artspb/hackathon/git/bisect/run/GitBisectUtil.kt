package me.artspb.hackathon.git.bisect.run

import com.intellij.dvcs.repo.Repository
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitCommandResult
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.util.GitFreezingProcess
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

internal val BISECT = write("bisect")

private fun write(@Suppress("SameParameterValue") name: String) = GitCommand::class.java
    .getDeclaredMethod("write", String::class.java)
    .apply { isAccessible = true }
    .invoke(null, name) as GitCommand

private val REVISION_REGEX = "\\[(.{40})]".toRegex()
private val BRANCH_REGEX = "Switched to branch \'(.*)\'".toRegex()

private val INDICATORS_KEY = Key.create<MutableList<ProgressIndicator>>("me.artspb.hackathon.git.bisect.run.GitBisectUtil.INDICATORS_KEY")

fun Project.addIndicator(indicator: ProgressIndicator): ProgressIndicator {
    getIndicators() += indicator
    return indicator
}

fun Project.cancelAllIndicators() = getIndicators().removeAll { it.cancel(); true }

private fun Project.getIndicators() = (this as UserDataHolderEx).putUserDataIfAbsent(INDICATORS_KEY, mutableListOf())

fun findRepositoryInBisect(project: Project, finalizer: (GitRepository?) -> Unit = {}) {
    val task = object : Task.Backgroundable(project, "Looking for a repository in bisect state...") {
        override fun run(indicator: ProgressIndicator) = GitFreezingProcess(project, "bisect") {
            val repository = GitUtil.getRepositories(project).find { it.isInBisect() }
            finalizer.invoke(repository)
        }.execute()
    }
    runAsynchronously(project, task)
}

fun GitRepository.isInBisect() = bisect("visualize", "--oneline").success()

fun GitRepository.bisect(vararg parameters: String, consumer: (GitCommandResult) -> Unit) {
    val task = object : Task.Backgroundable(project, "Running 'git bisect ${parameters[0]}'...") {
        override fun run(indicator: ProgressIndicator) = GitFreezingProcess(project, "bisect") {
            val result = this@bisect.bisect(*parameters)
            indicator.checkCanceled()
            if (result.success()) {
                updateAndRefresh(this@bisect)
            } else {
                // TODO bisect failed
            }
            indicator.checkCanceled()
            consumer.invoke(result)
        }.execute()
    }
    runAsynchronously(project, task)
}

fun cleanUpAfterBisect(project: Project, repository: GitRepository, line: String, finalizer: () -> Unit = {}) {
    val task = object : Task.Backgroundable(project, "Synchronizing branches...") {
        override fun run(indicator: ProgressIndicator) = GitFreezingProcess(project, "bisect") {
            val revisionResult = REVISION_REGEX.find(line)
            if (revisionResult != null) {
                val (revision) = revisionResult.destructured
                synchronizeRepositories(project, repository, revision, indicator)
            } else {
                tryResetAllBranches(project, repository, line, indicator)
            }
            indicator.checkCanceled()
            finalizer.invoke()
        }.execute()
    }
    runAsynchronously(project, task)
}

private fun synchronizeRepositories(@Suppress("UNUSED_PARAMETER") project: Project, repository: GitRepository, revision: String, indicator: ProgressIndicator) {
    val revisions = mutableMapOf(Pair(repository, revision))
    revisions[repository] = revision // otherwise bisect can get stuck

    indicator.isIndeterminate = false

    var count = 1
    for ((repo, rev) in revisions) {
        repo.checkout(indicator, rev, count++ / revisions.size.toDouble())
    }

    indicator.isIndeterminate = true
    indicator.text2 = ""
}

fun GitRepository?.reset(project: Project, onFinish: (GitCommandResult) -> Unit) {
    val task = object : Task.Backgroundable(project, "Running 'git bisect reset'...") {
        override fun run(indicator: ProgressIndicator) = GitFreezingProcess(project, "bisect") {
            var result: GitCommandResult = GitCommandResult.error("")
            for (repository in if (this@reset != null) listOf(this@reset) else GitUtil.getRepositories(project)) {
                result = repository.bisect("reset")
                indicator.checkCanceled()
                if (result.success()) {
                    val output = result.output
                    val firstLine = output.firstOrNull()
                    if (firstLine != null && firstLine != "We are not bisecting.") {
                        updateAndRefresh(repository)
                        if (output.size > 1) {
                            tryResetAllBranches(project, this@reset, output[1], indicator)
                        }
                        break
                    }
                } else {
                    // TODO reset failed
                }
            }
            onFinish.invoke(result)
        }.execute()
    }
    runAsynchronously(project, task)
}

fun VirtualFile.toRepository(project: Project, onFinish: (GitRepository) -> Unit) {
    val task = object : Task.Backgroundable(project, "Looking for a repository...") {
        override fun run(indicator: ProgressIndicator) = GitFreezingProcess(project, "bisect") {
            val repository = GitUtil.getRepositoryManager(project).getRepositoryForRoot(this@toRepository)!!
            onFinish.invoke(repository)
        }.execute()
    }
    runAsynchronously(project, task)
}

private fun runAsynchronously(project: Project, task: Task.Backgroundable) {
    val runnable = Runnable {
        if (!project.isDisposed) {
            val indicator = project.addIndicator(BackgroundableProcessIndicator(task))
            ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, indicator)
        }
    }
    if (ApplicationManager.getApplication().isDispatchThread) {
        runnable.run()
    } else {
        ApplicationManager.getApplication().invokeLater(runnable)
    }
}

private fun tryResetAllBranches(project: Project, repository: GitRepository?, secondLine: String, indicator: ProgressIndicator) {
    val branchResult = BRANCH_REGEX.find(secondLine)
    if (branchResult != null) {
        indicator.isIndeterminate = false

        val (branch) = branchResult.destructured
        val repositories = GitUtil.getRepositories(project).filterNot { it == repository }
        for ((i, it) in repositories.withIndex()) {
            it.checkout(indicator, branch, (i + 1.0) / repositories.size)
        }

        indicator.isIndeterminate = true
        indicator.text2 = ""
    }
}

private fun GitRepository.checkout(indicator: ProgressIndicator, revision: String, fraction: Double) {
    indicator.checkCanceled()
    indicator.text2 = "Checking out '$revision' in '$presentableUrl'"
    indicator.fraction = fraction

    Git.getInstance().checkout(this, revision, null, false, false)
    updateAndRefresh(this)
}

fun GitRepository.bisect(vararg parameters: String): GitCommandResult {
    val handler = GitLineHandler(project, root, BISECT)
    handler.addParameters(*parameters)
    return Git.getInstance().runCommand(handler)
}

fun updateAndRefresh(repository: GitRepository) {
    repository.update()
    GitUtil.refreshVfs(repository.root, null)
}

fun RunnerAndConfigurationSettings?.isRunnable(): Boolean =
    this != null && (!DumbService.getInstance(this.configuration.project).isDumb || this.type.isDumbAware)

@OptIn(ExperimentalContracts::class)
fun Project?.canRun(): Boolean {
    contract {
        returns(true) implies (this@canRun != null)
    }
    val project = this ?: return false
    if (GitBisectRunState.get(project)?.isInProgress == true) return false
    val repositories = GitUtil.getRepositories(project)
    if (repositories.isEmpty()) return false
    return repositories.all { it.state == Repository.State.NORMAL || it.state == Repository.State.DETACHED }
}