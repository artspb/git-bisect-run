package me.artspb.hackathon.git.bisect.run

import com.intellij.dvcs.DvcsUtil
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.ui.actions.ShowCommitInLogAction
import com.intellij.xml.util.XmlStringUtil
import git4idea.GitRevisionNumber
import git4idea.GitUtil
import git4idea.repo.GitRepository

@Suppress("PrivatePropertyName")
class GitBisectRunState(
    private val project: Project,
    private val dataContext: DataContext,
    private val repository: GitRepository,
    private val onZeroBehavior: Behavior,
    private val onNonZeroBehavior: Behavior,
    private val onNotStartedBehavior: Behavior,
    relyOnTests: Boolean
) : Disposable {

    companion object {

        private val KEY = Key.create<GitBisectRunState>("me.artspb.hackathon.git.bisect.run.GitBisectRunState.KEY")

        fun get(project: Project?): GitBisectRunState? = KEY.get(project)

        @Suppress("NAME_SHADOWING")
        fun start(
            project: Project,
            context: DataContext,
            file: VirtualFile?,
            bad: String? = null,
            good: String? = null
        ) {
            val repositories = GitUtil.getRepositories(project).toMutableList()
            val roots = GitUtil.getRootsFromRepositories(DvcsUtil.sortRepositories(repositories)).toList()
            val defaultRoot = DvcsUtil.findVcsRootFor(project, file)
            val dialog = GitBisectDialog(project, roots, defaultRoot, bad, good)
            if (dialog.showAndGet()) {
                val actions = dialog.getActions()
                val relyOnTests = dialog.relyOnTests()
                val (bad, good) = dialog.getSelectedParams()
                val root = dialog.getSelectedRepositoryRoot()
                root.toRepository(project) { repository ->
                    get(project)?.let { state ->
                        state.reset(false)
                        Disposer.dispose(state)
                    }
                    val state = GitBisectRunState(
                        project, context, repository, actions.first, actions.second, actions.third, relyOnTests
                    )
                    var oldState: GitBisectRunState? = null
                    while (oldState != state) {
                        oldState?.let { Disposer.dispose(it) }
                        oldState = (project as UserDataHolderEx).putUserDataIfAbsent(KEY, state)
                    }
                    state.start(bad, good)
                }
            }
        }

        fun reset(project: Project) {
            val state = get(project)
            if (state != null) {
                state.reset()
                Disposer.dispose(state)
            } else {
                findRepositoryInBisect(project) {
                    reset(project, it)
                }
            }
        }

        private fun reset(
            project: Project,
            repository: GitRepository?,
            allowWarning: Boolean = true
        ) {
            project.cancelAllIndicators()
            repository.reset(project) { result ->
                if (result.output.firstOrNull() == "We are not bisecting.") {
                    if (allowWarning) {
                        VcsNotifier.getInstance(project).notifyWarning(null, "", "Bisect reset not needed")
                    }
                } else if (result.success()) {
                    VcsNotifier.getInstance(project).notifySuccess(null, "", "Bisect reset succeeded")
                } else {
                    VcsNotifier.getInstance(project).notifyWeakError(null, "", "Bisect reset failed")
                }
            }
        }

        val DEFAULT_ON_ZERO_BEHAVIOR = Behavior.GOOD
        val DEFAULT_ON_NON_ZERO_BEHAVIOR = Behavior.BAD
        val DEFAULT_ON_NOT_STARTED_BEHAVIOR = Behavior.SKIP
    }

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, ExecutionEnvironmentListener())
        if (relyOnTests) {
            connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, TestEventsListener())
        }
    }

    private val GOOD_ACTION = NotificationAction.create("Good") { _, notification ->
        notification.expire()
        good()
    }

    private val BAD_ACTION = NotificationAction.create("Bad") { _, notification ->
        notification.expire()
        bad()
    }

    private val SKIP_ACTION = NotificationAction.create("Skip") { _, notification ->
        notification.expire()
        skip()
    }

    private val RESET_ACTION = NotificationAction.create("Reset") { _, notification ->
        notification.expire()
        reset()
        Disposer.dispose(this)
    }

    private fun retryAction(command: String) = NotificationAction.create("Retry $command") { _, notification ->
        notification.expire()
        bisectAndInvoke(command)
    }

    @Volatile
    private var environment: ExecutionEnvironment? = null

    @Volatile
    private var testsStarted = false

    @Volatile
    var isInProgress = false

    override fun dispose() {
        environment = null
        isInProgress = false
        (project as UserDataHolderEx).replace(KEY, this, null)
    }

    private fun start(bad: String, good: String) {
        isInProgress = true
        infoWithReset("Bisect in Progress")
        bisectAndInvoke("start", bad, good)
    }

    private fun good(@Suppress("UNUSED_PARAMETER") context: String = "") {
        environment = null
        bisectAndInvoke("good")
    }

    private fun bad(@Suppress("UNUSED_PARAMETER") context: String = "") {
        environment = null
        bisectAndInvoke("bad")
    }

    private fun skip(@Suppress("UNUSED_PARAMETER") context: String = "") {
        environment = null
        bisectAndInvoke("skip")
    }

    private fun ask(context: String) {
        environment = null
        val notification = createNotification("$context What to do?", "", NotificationType.INFORMATION)
        notification.addAction(GOOD_ACTION)
        notification.addAction(BAD_ACTION)
        notification.addAction(SKIP_ACTION)
        notification.addAction(RESET_ACTION)
        VcsNotifier.getInstance(project).notify(notification)
    }

    private fun reset(allowWarning: Boolean = true) {
        reset(project, repository, allowWarning)
    }

    private fun bisectAndInvoke(vararg parameters: String) {
        repository.bisect(*parameters) { result ->
            if (result.success()) {
                val output = result.output
                val firstLine = output.firstOrNull()
                if (firstLine == null || firstLine.endsWith("is the first bad commit")) {
                    if (output.size > 1) {
                        val secondLine = output[1]
                        cleanUpAfterBisect(project, repository, secondLine) {
                            val lines = output.subList(1, 6)
                                .asSequence()
                                .filter { it.isNotBlank() }
                                .map {
                                    if (it.startsWith("commit")) {
                                        it.substring(0, 35)
                                    } else {
                                        StringUtil.shortenTextWithEllipsis(it.trim(), 40, 0, true)
                                    }
                                }
                                .toList().toTypedArray()
                            infoWithResetAndCheckout(
                                "Bisect Finished",
                                XmlStringUtil.wrapInHtmlLines(*lines),
                                secondLine.substring("commit ".length)
                            )
                        }
                    }
                } else {
                    if (firstLine.contains("left to test after this")) {
                        val prefix = when (parameters[0]) {
                            "bad" -> "[BAD] "
                            "good" -> "[GOOD] "
                            "skip" -> "[SKIP] "
                            else -> ""
                        }
                        VcsNotifier.getInstance(project).notifyWarning(null, "", prefix + firstLine)
                    }
                    if (output.size > 1) {
                        cleanUpAfterBisect(project, repository, output[1]) {
                            ApplicationManager.getApplication().invokeLater { invoke() }
                        }
                    }
                }
            } else {
                environment = null
                isInProgress = false
                val lines = result.output.asSequence().filter { it.isNotBlank() }.toList().toTypedArray()
                val description = when {
                    result.errorOutput.isNotEmpty() -> result.errorOutputAsHtmlString
                    lines.isNotEmpty() -> XmlStringUtil.wrapInHtmlLines(*lines)
                    else -> ""
                }
                errorWithReset("Bisect Stopped Due to Error", description) { notification ->
                    parameters.firstOrNull()?.let { command -> notification.addAction(retryAction(command)) }
                }
            }
        }
    }

    private fun invoke() {
        val configuration = RunManager.getInstance(project).selectedConfiguration
        if (configuration != null) {
            if (configuration.isRunnable()) {
                val executor = DefaultRunExecutor.getRunExecutorInstance()
                val builder = ExecutionEnvironmentBuilder.create(executor, configuration)
                val env = builder.activeTarget().dataContext(dataContext).build()
                environment = env
                ExecutionManager.getInstance(project).restartRunProfile(env)
            } else {
                DumbService.getInstance(project).smartInvokeLater { invoke() }
            }
        } else {
            environment = null
            isInProgress = false
            errorWithReset("Run Configuration not Found")
        }
    }

    private fun infoWithReset(title: String, description: String = "", configure: (Notification) -> Unit = {}) {
        notifyWithReset(title, description, NotificationType.INFORMATION, configure)
    }

    private fun errorWithReset(title: String, description: String = "", configure: (Notification) -> Unit = {}) {
        notifyWithReset(title, description, NotificationType.ERROR, configure)
    }

    private fun notifyWithReset(
        title: String,
        description: String,
        type: NotificationType,
        configure: (Notification) -> Unit
    ) {
        val notification = createNotification(title, description, type)
        configure(notification)
        notification.addAction(RESET_ACTION)
        VcsNotifier.getInstance(project).notify(notification)
    }

    private fun infoWithResetAndCheckout(
        @Suppress("SameParameterValue") title: String,
        description: String,
        revision: String
    ) {
        val checkout = NotificationAction.create("Checkout") { _, _ ->
            cleanUpAfterBisect(project, repository, "[$revision]")
        }
        @Suppress("UnstableApiUsage") val show = NotificationAction.create("Show in Git log") { event, _ ->
            object : ShowCommitInLogAction() {
                override fun getRevisionNumber(event: AnActionEvent): VcsRevisionNumber = GitRevisionNumber(revision)
            }.actionPerformed(event)
        }

        val notification = createNotification(title, description, NotificationType.INFORMATION)
        notification.addAction(RESET_ACTION)
        notification.addAction(checkout)
        notification.addAction(show)
        VcsNotifier.getInstance(project).notify(notification)
    }

    private fun createNotification(title: String, description: String, type: NotificationType) =
        VcsNotifier.importantNotification().createNotification(title, description, type)

    private inner class ExecutionEnvironmentListener : ExecutionListener {

        override fun processNotStarted(id: String, env: ExecutionEnvironment) {
            if (env != environment) return
            onNotStartedBehavior.action(this@GitBisectRunState, "Process not started.")
        }

        override fun processTerminated(id: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
            if (env != environment || testsStarted) return
            if (exitCode == 0) {
                onZeroBehavior.action(this@GitBisectRunState, "Process finished with 0.")
            } else {
                onNonZeroBehavior.action(this@GitBisectRunState, "Process finished with $exitCode.")
            }
        }
    }

    private inner class TestEventsListener : SMTRunnerEventsAdapter() {

        override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
            ApplicationManager.getApplication().invokeLater {
                if (testsRoot.executionId != environment?.executionId) return@invokeLater
                testsStarted = true
            }
        }

        override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
            ApplicationManager.getApplication().invokeLater {
                if (testsRoot.executionId != environment?.executionId) return@invokeLater
                testsStarted = false
                if (testsRoot.isPassed || testsRoot.isIgnored) {
                    val passed = if (testsRoot.isPassed) "passed" else "ignored"
                    onZeroBehavior.action(this@GitBisectRunState, "Tests $passed.")
                } else {
                    onNonZeroBehavior.action(this@GitBisectRunState, "Tests failed.")
                }
            }
        }
    }

    @Suppress("unused")
    enum class Behavior(val action: (GitBisectRunState, String) -> Unit) {
        GOOD(GitBisectRunState::good),
        BAD(GitBisectRunState::bad),
        SKIP(GitBisectRunState::skip),
        ASK(GitBisectRunState::ask)
    }
}
