package dev.danilocordeiro.ledgermcp.config;

import dev.danilocordeiro.ledgermcp.adapter.persistence.JdbcProposalRepository;
import dev.danilocordeiro.ledgermcp.audit.AuditLog;
import dev.danilocordeiro.ledgermcp.audit.JdbcAuditLog;
import dev.danilocordeiro.ledgermcp.mcp.LedgerReadTools;
import dev.danilocordeiro.ledgermcp.mcp.ProposalTools;
import dev.danilocordeiro.ledgermcp.mcp.ToolCallAuditor;
import dev.danilocordeiro.ledgermcp.port.DivergenceReader;
import dev.danilocordeiro.ledgermcp.port.LedgerReader;
import dev.danilocordeiro.ledgermcp.port.TransactionPoster;
import dev.danilocordeiro.ledgermcp.proposal.ProposalRepository;
import dev.danilocordeiro.ledgermcp.proposal.ProposalService;
import dev.danilocordeiro.ledgermcp.security.LocalOperatorAuthentication;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration
class McpConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    LedgerReadTools ledgerReadTools(LedgerReader ledger, DivergenceReader recon) {
        return new LedgerReadTools(ledger, recon);
    }

    @Bean
    ProposalTools proposalTools(ProposalService proposals, LedgerReader ledger) {
        return new ProposalTools(proposals, ledger);
    }

    @Bean
    ProposalService proposalService(LedgerReader ledger, TransactionPoster poster, ProposalRepository proposals,
                                    Clock clock, LedgerMcpProperties properties) {
        return new ProposalService(ledger, poster, proposals, clock, properties.proposals().ttl(),
                properties.proposals().approvalsEnabled());
    }

    @Bean
    ProposalRepository proposalRepository(JdbcClient jdbc, JsonMapper json, DataSource dataSource) {
        return new JdbcProposalRepository(jdbc, json, isPostgres(dataSource));
    }

    @Bean
    AuditLog auditLog(JdbcClient jdbc, DataSource dataSource) {
        return new JdbcAuditLog(jdbc, isPostgres(dataSource));
    }

    /**
     * On stdio there is no bearer token. The person who started the process is the
     * caller; their name and scopes come from configuration.
     */
    @Bean
    ToolCallAuditor toolCallAuditor(AuditLog audit, JsonMapper json, Clock clock, LedgerMcpProperties properties,
                                    @Value("${spring.ai.mcp.server.stdio:false}") boolean stdio) {
        LedgerMcpProperties.Stdio local = properties.stdio();
        return new ToolCallAuditor(audit, json, clock,
                stdio ? () -> new LocalOperatorAuthentication(local.operator(), local.scopes()) : null);
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot inspect the proposal/audit database", e);
        }
    }
}
