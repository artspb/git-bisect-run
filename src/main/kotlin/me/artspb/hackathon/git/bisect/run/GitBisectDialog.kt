@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package me.artspb.hackathon.git.bisect.run

import com.intellij.dvcs.DvcsUtil
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.laf.darcula.DarculaUIUtil.BW
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.EmptySpacingConfiguration
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGapsY
import com.intellij.util.asSafely
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.GitBranch
import git4idea.GitRevisionNumber
import git4idea.GitTag
import git4idea.branch.GitBranchUtil
import git4idea.i18n.GitBundle
import git4idea.merge.GIT_REF_PROTOTYPE_VALUE
import git4idea.merge.dialog.*
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import git4idea.ui.ComboBoxWithAutoCompletion
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.util.Collections.synchronizedMap
import javax.swing.*

class GitBisectDialog(
    private val project: Project,
    private val roots: List<VirtualFile>,
    private val defaultRoot: VirtualFile?,
    bad: String? = null,
    good: String? = null,
) : DialogWrapper(project) {

    private val selectedOptions = mutableSetOf<GitBisectOption>()
    private val optionInfos = mutableMapOf<GitBisectOption, OptionInfo<GitBisectOption>>()

    private val repositories = DvcsUtil.sortRepositories(GitRepositoryManager.getInstance(project).repositories)
    private val localBranches = mutableListOf<GitBranch>()
    private val remoteBranches = mutableListOf<GitBranch>()
    private val tags = synchronizedMap(HashMap<VirtualFile, List<GitTag>>())

    private val rootField = createRepoField()

    private val badRevisionField = createBadRevisionField()
    private val goodRevisionField = createGoodRevisionField()

    private val onZeroComboBox = createComboBox()
    private val onNonZeroComboBox = createComboBox()
    private val onNotStartedComboBox = createComboBox()

    private val optionsPanel = GitOptionsPanel(::optionChosen, ::getOptionInfo)

    private val panel = createPanel()

    private var okActionTriggered = false

    private val badRevisionValidator = RevValidator(badRevisionField)
    private val goodRevisionValidator = RevValidator(goodRevisionField)

    private val helpAction = OpenInBrowserAction()

    init {
        title = "Bisect"
        setOKButtonText("Bisect")

        loadRefs()
        updateBaseFields()
        loadSettings(bad, good)
        init()
        updateUi()
        updateOkActionEnabled()

        invokeLater(ModalityState.stateForComponent(rootPane)) { loadTagsInBackground() }
    }

    override fun createCenterPanel() = panel

    override fun doValidateAll(): List<ValidationInfo> {
        val result = listOf(
            ::validateBisectInProgress,
            ::validateBadRevision,
            ::validateGoodRevision
        ).mapNotNull { it() }

        okActionTriggered = false

        return result
    }

    override fun createSouthPanel() =
        createSouthPanelWithOptionsDropDown(super.createSouthPanel(), createOptionsDropDown())

    private fun createSouthPanelWithOptionsDropDown(southPanel: JComponent, optionDropDown: DropDownLink<*>) =
        southPanel.apply {
            (southPanel.components[0] as JPanel).apply {
                (layout as BorderLayout).hgap = JBUI.scale(5)
                add(optionDropDown, BorderLayout.EAST)
            }
        }

    private fun createOptionsDropDown() = DropDownLink(GitBundle.message("merge.options.modify")) {
        createPopupBuilder().createPopup()
    }.apply {
        mnemonic = KeyEvent.VK_M
    }

    override fun getHelpAction(): Action = helpAction

    override fun getPreferredFocusedComponent() = badRevisionField

    override fun doOKAction() {
        try {
            saveSettings()
        } finally {
            super.doOKAction()
        }
    }

    override fun createDefaultActions() {
        super.createDefaultActions()
        myOKAction = object : OkAction() {
            override fun doAction(e: ActionEvent?) {
                okActionTriggered = true
                super.doAction(e)
            }
        }
    }

    fun getActions(): Triple<GitBisectRunState.Behavior, GitBisectRunState.Behavior, GitBisectRunState.Behavior> {
        val values = GitBisectRunState.Behavior.values()
        val onZero = values[onZeroComboBox.selectedIndex]
        val onNonZero = values[onNonZeroComboBox.selectedIndex]
        val onNotStarted = values[onNotStartedComboBox.selectedIndex]
        return Triple(onZero, onNonZero, onNotStarted)
    }

    fun relyOnTests(): Boolean = GitBisectOption.RELY_ON_TESTS in selectedOptions

    fun getSelectedParams(): Pair<String, String> {
        val bad = badRevisionField.getText().orEmpty()
        val good = goodRevisionField.getText().orEmpty()
        return Pair(bad, good)
    }

    fun getSelectedRepositoryRoot(): VirtualFile = rootField.item.root

    private fun getSelectedRepo() = rootField.item

    private fun saveSettings() {
        val props = PropertiesComponent.getInstance()
        props.setValue("GitBisectRunDialog.myBadComboBox", badRevisionField.getText())
        props.setValue("GitBisectRunDialog.myGoodComboBox", goodRevisionField.getText())
        props.setValue(
            "GitBisectRunDialog.myOnZeroComboBox", onZeroComboBox.selectedIndex,
            GitBisectRunState.DEFAULT_ON_ZERO_BEHAVIOR.ordinal
        )
        props.setValue(
            "GitBisectRunDialog.myOnNonZeroComboBox", onNonZeroComboBox.selectedIndex,
            GitBisectRunState.DEFAULT_ON_NON_ZERO_BEHAVIOR.ordinal
        )
        props.setValue(
            "GitBisectRunDialog.myOnNotStartedComboBox", onNotStartedComboBox.selectedIndex,
            GitBisectRunState.DEFAULT_ON_NOT_STARTED_BEHAVIOR.ordinal
        )
        props.setValue(
            "GitBisectRunDialog.myRelyOnTestsCheckBox",
            relyOnTests(),
            true
        )
    }

    private fun loadSettings(bad: String?, good: String?) {
        val props = PropertiesComponent.getInstance()
        val badRevision = bad ?: props.getValue("GitBisectRunDialog.myBadComboBox")
        if (!badRevision.isNullOrEmpty() && isValidRevision(badRevision)) {
            badRevisionField.item = badRevision
        }
        val goodRevision = good ?: props.getValue("GitBisectRunDialog.myGoodComboBox")
        if (!goodRevision.isNullOrEmpty() && isValidRevision(goodRevision)) {
            goodRevisionField.item = goodRevision
        }
        props.getInt(
            "GitBisectRunDialog.myOnZeroComboBox",
            GitBisectRunState.DEFAULT_ON_ZERO_BEHAVIOR.ordinal
        ).let {
            onZeroComboBox.selectedIndex = it
        }
        props.getInt(
            "GitBisectRunDialog.myOnNonZeroComboBox",
            GitBisectRunState.DEFAULT_ON_NON_ZERO_BEHAVIOR.ordinal
        ).let {
            onNonZeroComboBox.selectedIndex = it
        }
        props.getInt(
            "GitBisectRunDialog.myOnNotStartedComboBox",
            GitBisectRunState.DEFAULT_ON_NOT_STARTED_BEHAVIOR.ordinal
        ).let {
            onNotStartedComboBox.selectedIndex = it
        }
        if (props.getBoolean("GitBisectRunDialog.myRelyOnTestsCheckBox", true)) {
            selectedOptions += GitBisectOption.RELY_ON_TESTS
        }
    }

    private fun updateOkActionEnabled() {
        isOKActionEnabled = listOf(::validateBadRevision, ::validateGoodRevision).mapNotNull { it() }.isEmpty()
    }

    private fun getTags() = tags[getSelectedRepo().root] ?: emptyList()

    private fun validateBadRevision(): ValidationInfo? {
        val badRevision = badRevisionField.getText()

        if (badRevision.isNullOrEmpty()) {
            return ValidationInfo("Select revision", badRevisionField)
        }

        return badRevisionValidator.validate()
    }

    private fun validateGoodRevision(): ValidationInfo? {
        val goodRevision = goodRevisionField.getText()

        if (goodRevision.isNullOrEmpty()) {
            return ValidationInfo("Select revision", goodRevisionField)
        }

        return goodRevisionValidator.validate()
    }

    private fun isValidRevision(revision: String): Boolean {
        if (revision.isEmpty()) return true

        var result = false
        try {
            val task = ThrowableComputable<GitRevisionNumber, VcsException> {
                GitRevisionNumber.resolve(
                    project,
                    getSelectedRepositoryRoot(),
                    revision
                )
            }
            ProgressManager.getInstance()
                .runProcessWithProgressSynchronously(
                    task,
                    "Validating Revision…",
                    true,
                    project
                )

            result = true
        } catch (ignored: VcsException) {
        }
        return result
    }

    private fun validateBisectInProgress(): ValidationInfo? {
        val state = GitBisectRunState.get(project)
        if (state?.isInProgress == true) {
            return ValidationInfo("Bisect is already in progress for this Git root")
        }
        return null
    }

    private fun loadRefs() {
        localBranches.clear()
        remoteBranches.clear()

        val repository = getSelectedRepo()
        localBranches += GitBranchUtil.sortBranchesByName(repository.branches.localBranches)
        remoteBranches += GitBranchUtil.sortBranchesByName(repository.branches.remoteBranches)
    }

    private fun loadTagsInBackground() {
        val selectedRoot = getSelectedRepo().root
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, GitBundle.message("rebase.dialog.progress.loading.tags"), true) {
                override fun run(indicator: ProgressIndicator) {
                    val sortedRoots = LinkedHashSet<VirtualFile>(roots.size).apply {
                        add(selectedRoot)
                        if (defaultRoot != null) {
                            add(defaultRoot)
                        }
                        addAll(roots)
                    }

                    sortedRoots.forEach { root ->
                        val tagsInRepo = loadTags(root)
                        tags[root] = tagsInRepo
                        if (selectedRoot == root) {
                            UIUtil.invokeLaterIfNeeded {
                                updateBaseFields()
                            }
                        }
                    }
                }

                override fun onSuccess() {
                    updateBaseFields()
                }
            })
    }

    @RequiresBackgroundThread
    private fun loadTags(root: VirtualFile): List<GitTag> {
        try {
            return GitBranchUtil.getAllTags(project, root).map { GitTag(it) }
        } catch (e: VcsException) {
            LOG.warn("Failed to load tags for root: ${root.presentableUrl}", e)
        }
        return emptyList()
    }

    private fun updateBaseFields() {
        val newRefs = sequenceOf(localBranches, remoteBranches, getTags()).flatten().map { it.name }.toList()
        badRevisionField.updatePreserving { badRevisionField.mutableModel?.update(newRefs) }
        goodRevisionField.updatePreserving { goodRevisionField.mutableModel?.update(newRefs) }
    }

    private fun createPanel() = panel {
        customizeSpacingConfiguration(EmptySpacingConfiguration()) {
            row {
                if (showRootField()) {
                    cell(rootField)
                        .align(AlignX.FILL)
                        .resizableColumn()
                }
            }.customize(UnscaledGapsY(0, 6))

            row {
                cell(createCmdLabel())

                cell(badRevisionField)
                    .align(AlignX.FILL)
                    .resizableColumn()

                cell(goodRevisionField)
                    .align(AlignX.FILL)
                    .resizableColumn()
            }.customize(UnscaledGapsY(0, 6))

            row {
                cell(onZeroComboBox)
                    .label("On zero exit code:", LabelPosition.TOP)
                    .align(AlignX.FILL)
                    .resizableColumn()

                cell(onNonZeroComboBox)
                    .label("On non-zero exit code:", LabelPosition.TOP)
                    .align(AlignX.FILL)
                    .resizableColumn()

                cell(onNotStartedComboBox)
                    .label("On failed compilation:", LabelPosition.TOP)
                    .align(AlignX.FILL)
                    .resizableColumn()
            }.customize(UnscaledGapsY(0, 6))

            row {
                cell(optionsPanel)
            }
        }
    }

    private fun showRootField() = roots.size > 1

    private fun createCmdLabel() = CmdLabel(
        "git bisect start",
        JBUI.insets(1, 1, 1, 0),
        JBDimension(JBUI.scale(100), goodRevisionField.preferredSize.height, true)
    )

    private fun createRepoField() = createRepositoryField(repositories, defaultRoot).apply {
        addActionListener {
            loadRefs()
            updateBaseFields()
        }
    }

    private fun createRepositoryField(
        repositories: List<GitRepository>,
        defaultRoot: VirtualFile? = null
    ) = ComboBox(CollectionComboBoxModel(repositories)).apply {
        item = repositories.find { repo -> repo.root == defaultRoot } ?: repositories.first()
        renderer = SimpleListCellRenderer.create("") { DvcsUtil.getShortRepositoryName(it) }
        setUI(FlatComboBoxUI(outerInsets = JBUI.insets(BW.get(), BW.get(), BW.get(), 0)))
    }

    private fun createBadRevisionField() =
        ComboBoxWithAutoCompletion<String>(MutableCollectionComboBoxModel(), project).apply {
            prototypeDisplayValue = GIT_REF_PROTOTYPE_VALUE
            setMinimumAndPreferredWidth(JBUI.scale(SHORT_FIELD_LENGTH))
            setPlaceholder("bad revision")
            setUI(FlatComboBoxUI(outerInsets = JBUI.insets(BW.get(), 0, BW.get(), 0)))
            addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    updateOkActionEnabled()
                }
            })
        }

    private fun createGoodRevisionField() =
        ComboBoxWithAutoCompletion<String>(MutableCollectionComboBoxModel(), project).apply {
            prototypeDisplayValue = GIT_REF_PROTOTYPE_VALUE
            setMinimumAndPreferredWidth(JBUI.scale(SHORT_FIELD_LENGTH))
            setPlaceholder("good revision")
            setUI(FlatComboBoxUI(outerInsets = JBUI.insets(BW.get(), 0, BW.get(), 0)))
            addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    updateOkActionEnabled()
                }
            })
        }

    private fun createComboBox() = ComboBox(GitBisectRunState.Behavior.values()).apply {
        prototypeDisplayValue = GitBisectRunState.Behavior.GOOD
        setUI(FlatComboBoxUI(outerInsets = JBUI.insets(BW.get(), 0, BW.get(), 0)))
    }

    private fun createPopupBuilder() = GitOptionsPopupBuilder(
        project,
        "Add Bisect Options",
        { GitBisectOption.values().toList() },
        ::getOptionInfo, ::isOptionSelected, { true }, ::optionChosen
    )

    private fun getOptionInfo(option: GitBisectOption) = optionInfos.computeIfAbsent(option) {
        OptionInfo(option, option.option, option.description)
    }

    private fun isOptionSelected(option: GitBisectOption) = option in selectedOptions

    private fun optionChosen(option: GitBisectOption) {
        if (option !in selectedOptions) {
            selectedOptions += option
        } else {
            selectedOptions -= option
        }
        updateUi()
        updateOkActionEnabled()
    }

    private fun updateUi() {
        optionsPanel.rerender(selectedOptions)
        panel.invalidate()
        SwingUtilities.invokeLater {
            validate()
            pack()
        }
    }

    internal inner class RevValidator(private val field: ComboBoxWithAutoCompletion<String>) {

        private var lastValidatedRevision = ""
        private var lastValid = true

        fun validate(): ValidationInfo? {
            val revision = field.getText().orEmpty()

            if (!okActionTriggered) {
                return if (revision == lastValidatedRevision)
                    getValidationResult()
                else
                    null
            }

            lastValidatedRevision = revision
            lastValid = isValidRevision(lastValidatedRevision)

            return getValidationResult()
        }

        private fun getValidationResult() = if (lastValid)
            null
        else
            ValidationInfo("There is no such revision", field)
    }

    internal inner class OpenInBrowserAction : AbstractAction() {
        override fun actionPerformed(e: ActionEvent?) {
            BrowserUtil.open(HELP_URL)
        }
    }

    companion object {
        private val LOG = logger<GitBisectDialog>()
        private const val SHORT_FIELD_LENGTH = 220
        private const val HELP_URL = "https://artspb.me/series/git-bisect-run-plugin/"
    }

    private val JComboBox<String>.mutableModel get() = model.asSafely<MutableCollectionComboBoxModel<String>>()
}
