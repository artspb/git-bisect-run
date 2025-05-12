package me.artspb.hackathon.git.bisect.run

import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls

enum class GitBisectOption(
    @param:NonNls val option: String,
    @param:Nls val description: String
) {
    RELY_ON_TESTS("Rely on tests", "Test results prevail over exit code"),
}
