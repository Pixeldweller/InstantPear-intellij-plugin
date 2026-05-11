plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

group = "com.pixeldweller"
version = "0.5-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("com.google.code.gson:gson:2.11.0")
    // JNA is bundled with the IntelliJ Platform. Including it as
    // `implementation` ships a second copy and the plugin classloader ends
    // up with a half-initialized com.sun.jna.Native (native lib extraction
    // path collides). Use compileOnly so the platform provides it at runtime.
    compileOnly("net.java.dev.jna:jna:5.14.0")
    compileOnly("net.java.dev.jna:jna-platform:5.14.0")

    intellijPlatform {
        intellijIdea("2025.3.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        composeUI()

        bundledPlugin("com.intellij.java")
    }
}

tasks.register<JavaExec>("runTestServer") {
    group = "application"
    description = "Run the InstantPear test WebSocket server"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pixeldweller.instantpear.server.TestServerKt")
    standardInput = System.`in`
}

tasks.register<JavaExec>("runSecureTestServer") {
    group = "application"
    description = "Run the InstantPear secure (WSS) test WebSocket server"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pixeldweller.instantpear.server.TestSecureServerKt")
    standardInput = System.`in`
}

tasks.register<JavaExec>("runSockJsTestServer") {
    group = "application"
    description = "Run the InstantPear SockJS test server"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.pixeldweller.instantpear.server.SockJsTestServerKt")
    standardInput = System.`in`
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }

    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
