plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "com.spotifymcp"
version = "1.0.0"

val ktorVersion = "3.0.3"
val mcpSdkVersion = "0.4.0"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Ktor client (used by SpotifyClient and SpotifyAuth)
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // MCP Kotlin SDK (includes KtorSseServerTransport)
    implementation("io.modelcontextprotocol:kotlin-sdk:$mcpSdkVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
}

application {
    mainClass.set("com.spotifymcp.ApplicationKt")
}

// Fat JAR for easy deployment
tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.spotifymcp.ApplicationKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
