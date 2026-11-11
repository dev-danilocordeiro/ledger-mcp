package dev.danilocordeiro.ledgermcp.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledger")
public record LedgerMcpProperties(
        @DefaultValue("synthetic") Mode mode,
        @DefaultValue Proposals proposals,
        @DefaultValue Stdio stdio,
        @DefaultValue Live live) {

    public enum Mode {
        /** In-memory, deterministic synthetic data. No external services. */
        SYNTHETIC,
        /** ledger-query and ledger-core over HTTP, ledger-recon's database read-only. */
        LIVE
    }

    public record Proposals(
            @DefaultValue("15m") Duration ttl,
            @DefaultValue("true") boolean approvalsEnabled) {
    }

    public record Stdio(
            @DefaultValue("local-operator") String operator,
            @DefaultValue({"ledger:read", "recon:read", "ledger:propose"}) List<String> scopes) {
    }

    public record Live(@DefaultValue Service query, @DefaultValue Service core, @DefaultValue Recon recon) {
    }

    public record Service(String baseUrl, String bearerToken, @DefaultValue("5s") Duration timeout) {
    }

    public record Recon(String jdbcUrl, String username, String password) {
    }
}
