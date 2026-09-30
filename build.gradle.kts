// Shared JVM conventions. Business logic is never shared between services; the only
// shared module is :libs:contracts (proto stubs + event DTOs).
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.protobuf) apply false
}

subprojects {
    group = "dev.surge"

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = JavaLanguageVersion.of(21)
        }
        tasks.withType<JavaCompile>().configureEach {
            options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
        }
    }
}
