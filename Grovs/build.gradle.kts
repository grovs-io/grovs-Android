// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}

// Configure git to use the tracked hooks directory so all developers get the pre-commit hook.
tasks.register("installGitHooks") {
    val hooksDir = rootProject.file("hooks")
    onlyIf { hooksDir.exists() }
    doLast {
        exec {
            workingDir = rootProject.projectDir
            commandLine("git", "config", "core.hooksPath", hooksDir.absolutePath)
        }
        println("Git hooks configured from: ${hooksDir.absolutePath}")
    }
}

tasks.named("prepareKotlinBuildScriptModel") {
    dependsOn("installGitHooks")
}