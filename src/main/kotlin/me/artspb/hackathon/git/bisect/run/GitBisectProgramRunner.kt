package me.artspb.hackathon.git.bisect.run

import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.actionSystem.CommonDataKeys

class GitBisectProgramRunner : ProgramRunner<RunnerSettings> {

    override fun getRunnerId(): String = "GitBisectProgramRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (GitBisectExecutor.ID != executorId) return false
        val configuration = profile as? RunConfiguration
        return configuration?.project.canRun()
    }

    override fun execute(environment: ExecutionEnvironment) {
        val dataContext = environment.dataContext ?: return
        val file = dataContext.getData(CommonDataKeys.VIRTUAL_FILE)
        GitBisectRunState.start(environment.project, dataContext, file)
    }
}
