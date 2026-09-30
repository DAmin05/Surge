// Generated gRPC stubs from /proto and event DTOs. No business logic lives here.
plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

dependencies {
    api(platform(libs.grpc.bom))
    api(libs.protobuf.java)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    compileOnly(libs.javax.annotation)
}

sourceSets {
    main { proto { srcDir(rootProject.file("proto")) } }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protoc.get()}" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
    }
    generateProtoTasks {
        all().configureEach { plugins { create("grpc") } }
    }
}
