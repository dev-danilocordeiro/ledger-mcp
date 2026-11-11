package dev.danilocordeiro.ledgermcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.danilocordeiro.ledgermcp.adapter.synthetic.SyntheticDataset;
import dev.danilocordeiro.ledgermcp.support.TestContainers;
import dev.danilocordeiro.ledgermcp.support.ToolCalls;
import dev.danilocordeiro.ledgermcp.support.ToolCalls.Outcome;
import dev.danilocordeiro.ledgermcp.support.Tokens;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The deployed shape: Streamable HTTP, real JWTs from Keycloak, PostgreSQL for proposals
 * and audit, synthetic ledger data. Each test talks to the server through the MCP Java
 * SDK client, the same way an agent host does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("http")
class HttpTransportIT {

    static final String CLEARING = SyntheticDataset.CARD_SETTLEMENT_CLEARING.toString();
    static final String SUSPENSE = SyntheticDataset.SUSPENSE.toString();

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        TestContainers.startPostgres();
        TestContainers.startKeycloak();
        registry.add("spring.datasource.url", TestContainers.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestContainers.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestContainers.POSTGRES::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestContainers::issuerUri);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private final List<McpSyncClient> clients = new ArrayList<>();

    @AfterEach
    void closeClients() {
        clients.forEach(McpSyncClient::closeGracefully);
    }

    @Test
    void rejectsRequestsWithoutTokenAndPointsToTheAuthorizationServer() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> unauthorized = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(unauthorized.statusCode()).isEqualTo(401);
            String challenge = unauthorized.headers().firstValue("WWW-Authenticate").orElseThrow();
            Matcher metadataUrl = Pattern.compile("resource_metadata=\"?([^\",\\s]+)").matcher(challenge);
            assertThat(metadataUrl.find()).as("challenge %s", challenge).isTrue();

            HttpResponse<String> metadata = http.send(HttpRequest.newBuilder(URI.create(metadataUrl.group(1))).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(metadata.statusCode()).isEqualTo(200);
            assertThat(metadata.body()).contains(TestContainers.issuerUri());
        }
    }

    /**
     * The README's interactive login path: Claude Code as a public PKCE client with a fixed
     * callback port. A browser is needed to finish the flow; this checks that the realm
     * accepts the authorization request instead of rejecting the client or redirect URI.
     */
    @Test
    void devRealmAcceptsClaudeCodeAuthorizationRequests() throws Exception {
        String authorize = TestContainers.issuerUri() + "/protocol/openid-connect/auth"
                + "?client_id=claude-code-analyst&response_type=code"
                + "&redirect_uri=http%3A%2F%2Flocalhost%3A8095%2Fcallback"
                + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256";
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create(authorize)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(login.body()).contains("kc-form-login").doesNotContain("Invalid parameter");
        }
    }

    @Test
    void readerCanReadButNotPropose() {
        McpSyncClient reader = connect(Tokens.READER);

        assertThat(reader.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                "get_account_balance", "list_account_entries", "get_transaction", "list_reconciliation_divergences",
                "propose_reversal", "propose_adjustment", "get_proposal", "approve_proposal", "reject_proposal");

        JsonNode balance = ToolCalls.call(reader, "get_account_balance", Map.of("accountId", CLEARING)).json();
        assertThat(balance.path("accountName").asString()).isEqualTo("Card settlement clearing");
        assertThat(balance.path("currency").asString()).isEqualTo("BRL");

        JsonNode divergences = ToolCalls.call(reader, "list_reconciliation_divergences",
                Map.of("kind", "AMOUNT_MISMATCH", "from", "2026-10-01", "to", "2026-11-30")).json();
        assertThat(divergences.path("count").asInt()).isEqualTo(4);

        Outcome denied = ToolCalls.call(reader, "propose_adjustment", adjustment(1_000, "should be refused"));
        assertThat(denied.isError()).isTrue();
        assertThat(denied.text()).contains("requires the 'ledger:propose' scope");

        assertThat(auditOutcomes(Tokens.READER.principal(), "propose_adjustment")).containsOnly("DENIED");
        assertThat(auditOutcomes(Tokens.READER.principal(), "get_account_balance")).contains("OK").doesNotContain("DENIED", "ERROR");
    }

    @Test
    void proposalIsExecutedOnlyAfterAnotherPersonApprovesWithTheRightCode() {
        McpSyncClient agent = connect(Tokens.AGENT);
        McpSyncClient approver = connect(Tokens.APPROVER);
        long suspenseBefore = balance(approver, SUSPENSE);

        JsonNode proposal = ToolCalls.call(agent, "propose_adjustment",
                adjustment(4_078, "bank short-paid the settlement NN-001067")).json();
        String id = proposal.path("proposalId").asString();
        String code = proposal.path("confirmationCode").asString();
        assertThat(proposal.path("status").asString()).isEqualTo("PENDING");
        assertThat(proposal.path("postings")).hasSize(2);
        assertThat(balance(approver, SUSPENSE)).as("proposing must not post").isEqualTo(suspenseBefore);

        Outcome agentApproves = ToolCalls.call(agent, "approve_proposal", Map.of("proposalId", id, "confirmationCode", code));
        assertThat(agentApproves.text()).contains("requires the 'ledger:approve' scope");

        Outcome wrongCode = ToolCalls.call(approver, "approve_proposal", Map.of("proposalId", id, "confirmationCode", "0000-0000"));
        assertThat(wrongCode.text()).contains("Confirmation code does not match");

        JsonNode executed = ToolCalls.call(approver, "approve_proposal", Map.of("proposalId", id, "confirmationCode", code)).json();
        assertThat(executed.path("status").asString()).isEqualTo("EXECUTED");
        assertThat(executed.path("decidedBy").asString()).isEqualTo(Tokens.APPROVER.principal());
        assertThat(executed.path("executedTransactionId").asString()).isNotBlank();
        assertThat(balance(approver, SUSPENSE)).isEqualTo(suspenseBefore + 4_078);

        JsonNode again = ToolCalls.call(approver, "approve_proposal", Map.of("proposalId", id, "confirmationCode", code)).json();
        assertThat(again.path("executedTransactionId").asString())
                .as("approving twice must not post twice")
                .isEqualTo(executed.path("executedTransactionId").asString());
        assertThat(balance(approver, SUSPENSE)).isEqualTo(suspenseBefore + 4_078);

        String arguments = jdbc.sql("""
                        SELECT arguments::text FROM audit_event
                         WHERE phase = 'COMPLETED' AND tool = 'approve_proposal' AND principal = :p AND outcome = 'OK'
                         ORDER BY id LIMIT 1""")
                .param("p", Tokens.APPROVER.principal()).query(String.class).single();
        assertThat(arguments).contains(id);
    }

    @Test
    void fourEyesRuleStopsSelfApprovalEvenWithBothScopes() {
        McpSyncClient supervisor = connect(Tokens.SUPERVISOR);
        JsonNode proposal = ToolCalls.call(supervisor, "propose_adjustment",
                adjustment(500, "testing that nobody approves their own change")).json();

        Outcome selfApproval = ToolCalls.call(supervisor, "approve_proposal", Map.of(
                "proposalId", proposal.path("proposalId").asString(),
                "confirmationCode", proposal.path("confirmationCode").asString()));

        assertThat(selfApproval.isError()).isTrue();
        assertThat(selfApproval.text()).contains("Four-eyes rule");
        JsonNode after = ToolCalls.call(supervisor, "get_proposal",
                Map.of("proposalId", proposal.path("proposalId").asString())).json();
        assertThat(after.path("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void reversalCanBeProposedOnceRejectedAndProposedAgain() {
        McpSyncClient agent = connect(Tokens.AGENT);
        McpSyncClient approver = connect(Tokens.APPROVER);
        String transactionId = SyntheticDataset.transaction(40).toString();

        JsonNode first = ToolCalls.call(agent, "propose_reversal",
                Map.of("transactionId", transactionId, "reason", "customer says this deposit was duplicated")).json();
        assertThat(first.path("kind").asString()).isEqualTo("REVERSAL");

        Outcome duplicate = ToolCalls.call(agent, "propose_reversal",
                Map.of("transactionId", transactionId, "reason", "same request sent a second time"));
        assertThat(duplicate.text()).contains("already has reversal proposal " + first.path("proposalId").asString());

        JsonNode rejected = ToolCalls.call(approver, "reject_proposal",
                Map.of("proposalId", first.path("proposalId").asString(), "reason", "deposit is legitimate per bank")).json();
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");

        JsonNode second = ToolCalls.call(agent, "propose_reversal",
                Map.of("transactionId", transactionId, "reason", "second review found it duplicated")).json();
        assertThat(second.path("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void auditTrailIsAppendOnlyInTheDatabase() {
        McpSyncClient reader = connect(Tokens.READER);
        ToolCalls.call(reader, "get_account_balance", Map.of("accountId", CLEARING));

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_event SET principal = 'someone-else'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event").update())
                .hasMessageContaining("append-only");
    }

    private Map<String, Object> adjustment(long amountMinor, String reason) {
        Map<String, Object> args = new HashMap<>();
        args.put("accountId", SUSPENSE);
        args.put("offsetAccountId", CLEARING);
        args.put("direction", "DEBIT");
        args.put("amountMinor", amountMinor);
        args.put("currency", "BRL");
        args.put("reason", reason);
        args.put("divergenceReference", "NN-" + UUID.randomUUID().toString().substring(0, 6));
        return args;
    }

    private long balance(McpSyncClient client, String accountId) {
        return ToolCalls.call(client, "get_account_balance", Map.of("accountId", accountId)).json()
                .path("balanceMinor").asLong();
    }

    private List<String> auditOutcomes(String principal, String tool) {
        return jdbc.sql("SELECT outcome FROM audit_event WHERE phase = 'COMPLETED' AND principal = :p AND tool = :t ORDER BY id")
                .param("p", principal).param("t", tool).query(String.class).list();
    }

    private McpSyncClient connect(Tokens identity) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + identity.fetch()))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        clients.add(client);
        return client;
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
