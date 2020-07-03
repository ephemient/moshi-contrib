import org.gradle.api.plugins.internal.JvmPluginsHelper
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("kapt")
    id("org.jetbrains.dokka")
    id("org.jmailen.kotlinter")
    `maven-publish`
}

dependencies {
    val autoServiceVersion: String by project
    val incapVersion: String by project
    val junitVersion: String by project
    val kotlinCompileTestingVersion: String by project
    val kotlinpoetVersion: String by project
    val moshiVersion: String by project
    val truthVersion: String by project

    compileOnly("com.google.auto.service:auto-service-annotations:$autoServiceVersion")
    compileOnly("net.ltgt.gradle.incap:incap:$incapVersion")
    kapt("net.ltgt.gradle.incap:incap-processor:$incapVersion")
    kapt("com.google.auto.service:auto-service:$autoServiceVersion")
    implementation(project(":annotations"))
    implementation(kotlin("stdlib-jdk8"))
    implementation("com.squareup:kotlinpoet:$kotlinpoetVersion")
    implementation("com.squareup:kotlinpoet-metadata:$kotlinpoetVersion")
    implementation("com.squareup.moshi:moshi:$moshiVersion")
    testImplementation("com.github.tschuchortdev:kotlin-compile-testing:$kotlinCompileTestingVersion")
    testImplementation("com.google.truth:truth:$truthVersion")
    testImplementation("com.squareup.moshi:moshi-kotlin-codegen:$moshiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitVersion")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
}

tasks.withType<Test> {
    useJUnitPlatform()
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
