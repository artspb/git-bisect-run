# git bisect run

IntelliJ Platform plugin (Kotlin) that runs `git bisect run` with an IDE run configuration as the test command.
It's built from the IntelliJ Platform Plugin Template, using IntelliJ Platform Gradle Plugin 2.x.

## Commands

- `./gradlew build`: compile, tests, and plugin packaging. Run it before committing.
- `./gradlew buildPlugin`: zip in `build/distributions/`.
- `./gradlew verifyPlugin`: Plugin Verifier, the same check CI runs.
- `./gradlew runIde`: sandbox IDE (also the `.run/Run Plugin` configuration).

## Supporting a new IDE version

The pattern in Git history is two commits on `main`, then a tag.

1. **`Increase the compatibility range to include <YYYY.N>`**, touching `gradle.properties` only:
   - Set `pluginUntilBuild` to the new branch. The branch number is the two-digit year plus the release number, so 2026.3 is `263.*` and 2027.1 is `271.*`.
   - Raise `pluginSinceBuild` only when the code starts depending on a newer platform API.
   - Fix anything the new platform breaks. Usually that means adding content modules (see below).
2. **`Release <X.Y.Z>`**, touching `gradle.properties` and `CHANGELOG.md`:
   - Bump `pluginVersion` (patch bump).
   - Add a section directly under `## [Unreleased]`:
     ```
     ## [X.Y.Z]
     - YYYY.N compatibility.
     ```
3. **Tag** the release commit with a lightweight tag `vX.Y.Z`: `git tag vX.Y.Z <release-commit>`. Don't forget this step.

The CI release workflow then adds a `Changelog update - vX.Y.Z` commit to `main`. Pull before starting the next update.

### Building against an unreleased platform

To compile against an EAP or nightly build, set `platformVersion` to e.g. `271-SNAPSHOT` and add `nightly()` to
`repositories { intellijPlatform { … } }` in `build.gradle.kts`. These are usually local experiments; the committed
state builds against an EAP that `defaultRepositories()` can resolve (currently `262-EAP-SNAPSHOT`).
Make sure `./gradlew build` passes against both the committed `platformVersion` and the new one.

### Content modules: the usual source of breakage

Newer platforms keep moving classes out of the core into content modules. A plugin has to declare those modules in
`platformBundledModules` in `gradle.properties` (passed to `bundledModules(...)`). Otherwise compilation fails with
`Unresolved reference`, or with `MISSING_DEPENDENCY_SUPERCLASS` for a supertype of a Git4Idea class.
`plugin.xml` doesn't need to change: at runtime the modules come through the `Git4Idea` dependency.

To find the module that owns a missing class, search the extracted IDE in the Gradle cache. The jar name is the module name:

```sh
for j in $(find ~/.gradle/caches/*/transforms/*/transformed/idea-<VERSION> -name '*.jar'); do
  unzip -l "$j" 2>/dev/null | grep -q 'com/intellij/openapi/vcs/VcsException.class' && echo "$j"
done
```

Modules added for 2027.1:
- `intellij.platform.execution`: `Executor`, `RunManager`, `ProgramRunner`, `ExecutionEnvironment`, and the rest of the execution API.
- `intellij.platform.vcs.core`: `VcsException`.
- `intellij.platform.vcs.shared`: `ShortVcsRevisionNumber`, a supertype of `GitRevisionNumber`.

Adding a module that also exists in the older platform is harmless, so the module list can stay the same for both.

## Code notes

- `GitBisectDialog.kt` reuses Git4Idea's internal merge dialog UI (`git4idea.merge.dialog.*`) through
  `@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")`. Internal Git4Idea changes break it first.
- `GitBisectRunState` intentionally uses the `@Internal`/`@OverrideOnly` `ShowCommitInLogAction`. `verifyPlugin`'s
  `failureLevel` in `build.gradle.kts` excludes `INTERNAL_API_USAGES` and `OVERRIDE_ONLY_API_USAGES` for that reason,
  and `verifier-ignored-problems.txt` lists the known violations.
- The `pluginVerification.ides` list in `build.gradle.kts` still names 2025.1 IDEs, which are older than
  `pluginSinceBuild`. Updating it to the supported range would make `verifyPlugin` check something useful.

## Housekeeping

- Dependabot bumps the Gradle wrapper, Gradle plugins, and Actions. The `gradleVersion` property in `gradle.properties`
  only feeds the `wrapper` task and can lag behind `gradle/wrapper/gradle-wrapper.properties`.
