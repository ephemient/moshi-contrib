plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("org.jmailen.kotlinter")
    `maven-publish`
}

dependencies {
    compileOnly(kotlin("stdlib-jdk8"))
}

java {
    withSourcesJar()
}

tasks.withType<Jar> {
    if (name.capitalize().contains(JavaPlugin.JAVADOC_TASK_NAME.capitalize())) {
        isEnabled = false
    }
}

val dokkaJar by tasks.registering(Jar::class) {
    group = BasePlugin.BUILD_GROUP
    description = "Assembles a jar archive containing the main kdoc."
    classifier = DocsType.JAVADOC
    from(tasks["dokka"])
    into(JavaPlugin.JAVADOC_ELEMENTS_CONFIGURATION_NAME)
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(dokkaJar.get())
        }
    }
}
