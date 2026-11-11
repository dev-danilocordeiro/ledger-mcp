package dev.danilocordeiro.ledgermcp.adapter.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import dev.danilocordeiro.ledgermcp.adapter.persistence.JdbcProposalRepository;
import dev.danilocordeiro.ledgermcp.domain.Direction;
import dev.danilocordeiro.ledgermcp.domain.DivergenceKind;
import dev.danilocordeiro.ledgermcp.domain.StatementPage;
import dev.danilocordeiro.ledgermcp.domain.UnsupportedCapabilityException;
import dev.danilocordeiro.ledgermcp.proposal.Proposal;
import dev.danilocordeiro.ledgermcp.proposal.ProposalRuleException;
import dev.danilocordeiro.ledgermcp.proposal.ProposalService;
import dev.danilocordeiro.ledgermcp.proposal.ProposalStatus;
import dev.danilocordeiro.ledgermcp.support.TestContainers;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live adapters against stand-ins for the sibling services: WireMock speaking
 * ledger-query's and ledger-core's HTTP contracts, and PostgreSQL holding ledger-recon's
 * {@code reconciliation_result} table exactly as ledger-recon creates it.
 */
class LiveAdaptersIT {

    static final UUID CASH = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    static final UUID SUSPENSE = UUID.fromString("a0000000-0000-4000-8000-000000000005");

    @SuppressWarnings("resource")
    static final GenericContainer<?> WIREMOCK = new GenericContainer<>("wiremock/wiremock:3.13.2")
            .withExposedPorts(8080)
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("src/test/resources/wiremock")), "/home/wiremock")
            .waitingFor(Wait.forHttp("/__admin/health").forStatusCode(200));

    static final JsonMapper JSON = JsonMapper.builder().build();
    static HikariDataSource dataSource;
    static JdbcClient jdbc;
    static String wiremockUrl;

    @BeforeAll
    static void start() {
        WIREMOCK.start();
        TestContainers.startPostgres();
        wiremockUrl = "http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(8080);
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(TestContainers.POSTGRES.getJdbcUrl());
        dataSource.setUsername(TestContainers.POSTGRES.getUsername());
        dataSource.setPassword(TestContainers.POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc.sql("""
                CREATE TABLE IF NOT EXISTS reconciliation_result (
                    transaction_id TEXT PRIMARY KEY,
                    matched        BOOLEAN NOT NULL,
                    report         JSONB NOT NULL,
                    processed_at   TIMESTAMPTZ NOT NULL
                )""").update();
        jdbc.sql("DELETE FROM reconciliation_result").update();
        jdbc.sql("""
                INSERT INTO reconciliation_result VALUES
                  ('tx-ok', true, '{"matched":[{"reference":"NN-1","ledgerAmountMinor":10,"cnabAmountMinor":10}],"divergences":[]}', '2026-10-05T06:00:00Z'),
                  ('tx-short', false, '{"matched":[],"divergences":[{"kind":"amount_mismatch","reference":"NN-2","ledgerAmountMinor":5000,"cnabAmountMinor":4900}]}', '2026-10-06T06:00:00Z'),
                  ('tx-missing', false, '{"matched":[],"divergences":[{"kind":"missing_in_cnab","reference":"NN-3","ledgerAmountMinor":700}]}', '2026-10-20T06:00:00Z'),
                  ('tx-old', false, '{"matched":[],"divergences":[{"kind":"missing_in_cnab","reference":"NN-0","ledgerAmountMinor":1}]}', '2026-09-01T06:00:00Z')
                """).update();
    }

    @AfterAll
    static void stop() {
        dataSource.close();
        WIREMOCK.stop();
    }

    @Test
    void readsBalanceAndTreats404AsUnknownAccount() {
        LedgerQueryClient query = new LedgerQueryClient(http());

        assertThat(query.balance(CASH)).get().satisfies(b -> {
            assertThat(b.balanceMinor()).isEqualTo(1_250_000);
            assertThat(b.currency()).isEqualTo("BRL");
            assertThat(b.normalSide()).as("ledger-query does not expose it").isNull();
        });
        assertThat(query.balance(UUID.randomUUID())).isEmpty();
    }

    @Test
    void passesInclusiveDateBoundsAndFollowsTheCursor() {
        LedgerQueryClient query = new LedgerQueryClient(http());

        StatementPage first = query.statement(CASH, Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-31T23:59:59.999999999Z"), 2, null);
        assertThat(first.entries()).hasSize(2);
        assertThat(first.entries().get(1).signedMinor()).isEqualTo(-700);
        assertThat(first.nextCursor()).isEqualTo("cursor-page-2");

        StatementPage second = query.statement(CASH, Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-31T23:59:59.999999999Z"), 2, first.nextCursor());
        assertThat(second.entries()).singleElement().satisfies(e -> assertThat(e.direction()).isEqualTo(Direction.DEBIT));
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void transactionLookupIsReportedAsUnsupported() {
        LedgerQueryClient query = new LedgerQueryClient(http());
        assertThatThrownBy(() -> query.transaction(UUID.randomUUID()))
                .isInstanceOf(UnsupportedCapabilityException.class)
                .hasMessageContaining("propose an adjustment instead");
    }

    @Test
    void readsOnlyUnmatchedReconResultsInRangeAndKind() {
        ReconDivergenceReader recon = new ReconDivergenceReader(jdbc, JSON);

        assertThat(recon.divergences(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-31T23:59:59Z"), null, 50))
                .extracting(d -> d.reference())
                .containsExactly("NN-3", "NN-2");
        assertThat(recon.divergences(null, null, DivergenceKind.AMOUNT_MISMATCH, 50))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.transactionId()).isEqualTo("tx-short");
                    assertThat(d.ledgerAmountMinor()).isEqualTo(5000);
                    assertThat(d.cnabAmountMinor()).isEqualTo(4900);
                });
        assertThat(recon.divergences(null, null, DivergenceKind.MISSING_IN_CNAB, 50))
                .allSatisfy(d -> assertThat(d.cnabAmountMinor()).isNull());
    }

    @Test
    void approvedAdjustmentIsPostedToLedgerCoreWithTheProposalIdAsIdempotencyKey() throws Exception {
        ProposalService service = liveService();
        Proposal proposal = service.proposeAdjustment(SUSPENSE, CASH, Direction.DEBIT, 1_240, "BRL",
                "bank short-paid NN-2 per reconciliation", "NN-2", "analyst");

        Proposal executed = service.approve(proposal.id(), proposal.confirmationCode(), "approver");

        assertThat(executed.status()).isEqualTo(ProposalStatus.EXECUTED);
        assertThat(executed.executedTransactionId()).isEqualTo(UUID.fromString("c0000000-0000-4000-8000-000000000001"));
        JsonNode sent = lastRequestTo("/v1/transactions");
        assertThat(sent.path("headers").path("Idempotency-Key").asString()).isEqualTo(proposal.id().toString());
        JsonNode body = JSON.readTree(sent.path("body").asString());
        assertThat(body.path("currency").asString()).isEqualTo("BRL");
        assertThat(body.path("externalReference").asString()).isEqualTo("adjustment-for:NN-2");
        assertThat(body.path("entries")).hasSize(2);
        assertThat(body.path("entries").get(0).path("amountMinorUnits").asLong()).isEqualTo(1_240);
    }

    @Test
    void ledgerRejectionMarksTheProposalFailed() {
        ProposalService service = liveService();
        Proposal proposal = service.proposeAdjustment(SUSPENSE, CASH, Direction.CREDIT, 999_999_999, "BRL",
                "far more than the account holds", null, "analyst");

        assertThatThrownBy(() -> service.approve(proposal.id(), proposal.confirmationCode(), "approver"))
                .isInstanceOf(ProposalRuleException.class)
                .hasMessageContaining("insufficient funds");
        assertThat(service.get(proposal.id()).status()).isEqualTo(ProposalStatus.FAILED);
    }

    /** Same client the application builds for live mode. */
    private static RestClient http() {
        return LiveHttp.restClient(wiremockUrl, null, Duration.ofSeconds(5));
    }

    private ProposalService liveService() {
        return new ProposalService(
                new LedgerQueryClient(http()),
                new LedgerCoreClient(http(), JSON),
                new JdbcProposalRepository(jdbc, JSON, true),
                Clock.systemUTC(), Duration.ofMinutes(15), true);
    }

    private static JsonNode lastRequestTo(String url) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            String body = http.send(HttpRequest.newBuilder(URI.create(wiremockUrl + "/__admin/requests?limit=50")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            for (JsonNode entry : JSON.readTree(body).path("requests")) {
                JsonNode request = entry.path("request");
                if (request.path("url").asString().equals(url) && entry.path("responseDefinition").path("status").asInt() == 201) {
                    return request;
                }
            }
        }
        throw new AssertionError("no successful request to " + url);
    }
}
