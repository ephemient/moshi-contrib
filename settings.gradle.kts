pluginManagement {
    plugins {
        val detektVersion: String by settings
        val dokkaVersion: String by settings
        val kotlinterVersion: String by settings
        val versionsPluginVersion: String by settings

        id("io.gitlab.arturbosch.detekt") version detektVersion
        id("org.jetbrains.dokka") version dokkaVersion
        id("org.jmailen.kotlinter") version kotlinterVersion
        id("com.github.ben-manes.versions") version versionsPluginVersion
    }
}
rootProject.name = "moshi-contrib"
include("annotations", "processor")
