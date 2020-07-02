import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.github.ben-manes.versions")
    id("io.gitlab.arturbosch.detekt")
}

group = System.getenv("GROUP") ?: "com.github.ephemient.moshi-contrib"
version = System.getenv("VERSION") ?: run {
    val process = ProcessBuilder("git", "describe", "--always", "--dirty=-SNAPSHOT")
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .start()
    process.inputStream.bufferedReader().readText()
        .also { process.waitFor() }
        .takeIf { process.exitValue() == 0 && !it.isBlank() }
        ?.trim() ?: "unspecified-SNAPSHOT"
}

detekt {
    val detektVersion: String by project

    toolVersion = detektVersion
    input = files(allprojects.map { "${it.projectDir}/src/main/kotlin" })
    config = files("detekt.yml")
    buildUponDefaultConfig = true
}

val check by tasks.registering {
    group = JavaBasePlugin.VERIFICATION_GROUP
    description = "Run all checks."
}

tasks.withType<Detekt> {
    jvmTarget = "1.8"
    check.get().dependsOn(this)
}

fun isVersionUnstable(version: String): Boolean =
    listOf("-M", "-rc", "-alpha", "-beta").any { it in version }

tasks.withType<DependencyUpdatesTask> {
    rejectVersionIf {
        isVersionUnstable(candidate.version) && !isVersionUnstable(currentVersion)
    }
}

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        google()
        jcenter()
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }
}
