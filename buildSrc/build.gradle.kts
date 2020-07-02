plugins {
    `kotlin-dsl`
    id("java-gradle-plugin")
}

buildscript {
    repositories {
        google()
        jcenter()
    }
}

dependencies {
    implementation(gradleApi())

    implementation(kotlin("gradle-plugin", "1.3.72"))
    implementation(kotlin("stdlib-jdk8"))
}

repositories {
    google()
    jcenter()
}
