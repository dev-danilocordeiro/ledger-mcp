plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.danilocordeiro"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

extra["springAiVersion"] = "2.0.2"

dependencyManagement {
    imports { mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}") }
}

// Eval harness: a separate source set so the Anthropic SDK never lands on the server's
// runtime classpath. Run with ./gradlew evals (needs ANTHROPIC_API_KEY or an `ant` profile).
val evals: SourceSet by sourceSets.creating

dependencies {
    // MCP server: Streamable HTTP over WebMVC, or stdio when spring.ai.mcp.server.stdio=true.
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    // OAuth 2 resource server for the MCP endpoint (Protected Resource Metadata, 401 challenge).
    implementation("org.springaicommunity:mcp-server-security-spring-boot:0.1.14")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Audit trail is written by an aspect around every @McpTool method.
    implementation("org.springframework.boot:spring-boot-starter-aspectj")

    // Proposals and audit events. Postgres for the HTTP deployment, H2 file for local stdio.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // compile scope: the H2 append-only trigger implements org.h2.api.Trigger
    implementation("com.h2database:h2")

    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "evalsImplementation"("com.anthropic:anthropic-java:3.3.0")
    // Same MCP SDK release Spring AI 2.0.2 ships with on the server side.
    "evalsImplementation"("io.modelcontextprotocol.sdk:mcp-core:2.1.0")
    "evalsImplementation"("io.modelcontextprotocol.sdk:mcp-json-jackson3:2.1.0")
    "evalsImplementation"("tools.jackson.core:jackson-databind")
    "evalsRuntimeOnly"("org.slf4j:slf4j-nop")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}

val bootJar = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")

tasks.test {
    useJUnitPlatform()
    // The stdio integration test launches the packaged server as a child process,
    // exactly the way Claude Desktop and Claude Code do.
    dependsOn(bootJar)
    systemProperty("ledgerMcp.jar", bootJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("ledgerMcp.evalsFile", file("evals/questions.jsonl").absolutePath)
}

tasks.register<JavaExec>("evals") {
    group = "verification"
    description = "Asks Claude the questions in evals/questions.jsonl and scores its tool choice."
    dependsOn(bootJar)
    classpath = evals.runtimeClasspath
    mainClass.set("dev.danilocordeiro.ledgermcp.evals.ToolSelectionEval")
    args(
        "--jar", bootJar.get().archiveFile.get().asFile.absolutePath,
        "--questions", file("evals/questions.jsonl").absolutePath,
        "--report", layout.buildDirectory.file("evals/report.json").get().asFile.absolutePath,
    )
    (project.findProperty("evalModel") as String?)?.let { args("--model", it) }
    if (project.hasProperty("dryRun")) {
        args("--dry-run", "true")
    }
}
