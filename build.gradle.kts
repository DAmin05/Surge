// Shared JVM conventions. Business logic is never shared between services; the only
// shared module is :libs:contracts (proto stubs + event DTOs).
plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.protobuf) apply false
}

subprojects {
    group = "dev.surge"

    // Every Spring Boot service ships the OpenTelemetry Java agent next to its jar
    // (build/otel/opentelemetry-javaagent.jar); the image starts it with -javaagent.
    pluginManager.withPlugin("org.springframework.boot") {
        val otelAgent = configurations.create("otelAgent") { isTransitive = false }
        dependencies.add(otelAgent.name, rootProject.libs.otel.javaagent)
        val copyOtelAgent = tasks.register<Copy>("copyOtelAgent") {
            from(otelAgent)
            into(layout.buildDirectory.dir("otel"))
            rename { "opentelemetry-javaagent.jar" }
        }
        tasks.named("bootJar") { dependsOn(copyOtelAgent) }
    }

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
