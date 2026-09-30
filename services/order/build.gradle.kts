plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":libs:contracts"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.jackson.databind)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.kafka.clients)
    // API only; the Java agent supplies the implementation at runtime (no-op in tests).
    implementation(libs.otel.api)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.micrometer.prometheus)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.redpanda)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.bootJar { archiveFileName = "order.jar" }
tasks.jar { enabled = false }
