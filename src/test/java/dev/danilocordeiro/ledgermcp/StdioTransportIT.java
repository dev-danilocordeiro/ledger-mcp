package dev.danilocordeiro.ledgermcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.danilocordeiro.ledgermcp.support.ToolCalls;
import dev.danilocordeiro.ledgermcp.support.ToolCalls.Outcome;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Launches the packaged jar as a child process and talks JSON-RPC over its stdin and
 * stdout, which is exactly what Claude Desktop and Claude Code do with a stdio server.
 */
class StdioTransportIT {

    @TempDir
    static Path home;

    static McpSyncClient client;
    static StdioClientTransport transport;
    static final StringBuilder stderr = new StringBuilder();

    @BeforeAll
    static void launch() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ServerParameters server = ServerParameters.builder(java)
                .args("-jar", System.getProperty("ledgerMcp.jar"), "--spring.profiles.active=stdio")
                .addEnvVar("LEDGER_MCP_HOME", home.toString())
                .addEnvVar("LEDGER_MCP_OPERATOR", "alice")
                .build();
        transport = new StdioClientTransport(server, McpJsonDefaults.getMapper());
        transport.setStdErrorHandler(stderr::append);
        client = McpClient.sync(transport)
                .initializationTimeout(Duration.ofSeconds(60))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        client.initialize();
    }

    @AfterAll
    static void shutdown() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    @Test
    void exposesTheNineToolsWithModelFacingDescriptions() {
        List<McpSchema.Tool> tools = client.listTools().tools();

        assertThat(tools).hasSize(9);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description()).as(tool.name()).contains("Use ").contains("Do not use");
            assertThat(tool.annotations()).as(tool.name()).isNotNull();
        });
        assertThat(tools).filteredOn(t -> t.name().startsWith("get_") || t.name().startsWith("list_"))
                .allSatisfy(t -> assertThat(t.annotations().readOnlyHint()).as(t.name()).isTrue());
        assertThat(tools).filteredOn(t -> t.name().equals("approve_proposal"))
                .singleElement().satisfies(t -> assertThat(t.annotations().destructiveHint()).isTrue());
    }

    @Test
    void readsWithTheLocalOperatorIdentityAndNeverApproves() {
        JsonNode balance = ToolCalls.call(client, "get_account_balance",
                Map.of("accountId", "a0000000-0000-4000-8000-000000000001")).json();
        assertThat(balance.path("balance").asString()).endsWith(" BRL");

        JsonNode proposal = ToolCalls.call(client, "propose_reversal", Map.of(
                "transactionId", "b0000000-0000-4000-8000-000000000012",
                "reason", "testing the stdio transport end to end")).json();
        assertThat(proposal.path("proposedBy").asString()).isEqualTo("alice");

        Outcome approve = ToolCalls.call(client, "approve_proposal", Map.of(
                "proposalId", proposal.path("proposalId").asString(),
                "confirmationCode", proposal.path("confirmationCode").asString()));
        assertThat(approve.isError()).isTrue();
        assertThat(approve.text()).contains("requires the 'ledger:approve' scope");
    }

    @Test
    void writesNothingButProtocolMessagesToStdout() {
        ToolCalls.call(client, "get_account_balance", Map.of("accountId", "not-a-uuid"));
        // A stray log line on stdout would have broken the JSON-RPC stream and failed every call above.
        assertThat(Files.exists(home.resolve("ledger-mcp.log"))).isTrue();
        assertThat(stderr.toString()).doesNotContain("Exception");
    }

    @Test
    void everyEvalQuestionExpectsAToolThatExists() throws Exception {
        Set<String> tools = client.listTools().tools().stream().map(McpSchema.Tool::name).collect(Collectors.toSet());
        JsonMapper json = JsonMapper.builder().build();
        List<JsonNode> questions = Files.readAllLines(Path.of(System.getProperty("ledgerMcp.evalsFile"))).stream()
                .filter(line -> !line.isBlank())
                .map(json::readTree)
                .toList();

        assertThat(questions).hasSize(20);
        assertThat(questions).extracting(q -> q.path("expected_tool").asString()).allMatch(tools::contains);
        assertThat(questions).extracting(q -> q.path("id").asString()).doesNotHaveDuplicates();
        assertThat(questions.stream().map(q -> q.path("expected_tool").asString()).collect(Collectors.toSet()))
                .as("every tool is the right answer at least once")
                .containsAll(tools);
    }
}
