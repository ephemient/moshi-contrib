import org.gradle.api.plugins.internal.JvmPluginsHelper

plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("org.jmailen.kotlinter")
    `maven-publish`
}

dependencies {
    val moshiVersion: String by project

    compileOnly(kotlin("stdlib-jdk8"))
    compileOnly("com.squareup.moshi:moshi:$moshiVersion")
}

java {
    withSourcesJar()
}

when (val javadocJarTaskName = sourceSets.main.get().javadocJarTaskName) {
    in tasks.names -> tasks.named(javadocJarTaskName, Jar::class) {
        isEnabled = false
    }
}

JvmPluginsHelper.configureDocumentationVariantWithArtifact(
    JavaPlugin.JAVADOC_ELEMENTS_CONFIGURATION_NAME,
    null,
    DocsType.JAVADOC,
    listOf(),
    "dokkaJar",
    tasks.named("dokka"),
    JvmPluginsHelper.findJavaComponent(components),
    configurations,
    tasks,
    objects
)

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
