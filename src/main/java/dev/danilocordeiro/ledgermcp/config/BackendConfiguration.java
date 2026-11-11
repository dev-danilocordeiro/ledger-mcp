package dev.danilocordeiro.ledgermcp.config;

import com.zaxxer.hikari.HikariDataSource;
import dev.danilocordeiro.ledgermcp.adapter.live.LedgerCoreClient;
import dev.danilocordeiro.ledgermcp.adapter.live.LedgerQueryClient;
import dev.danilocordeiro.ledgermcp.adapter.live.LiveHttp;
import dev.danilocordeiro.ledgermcp.adapter.live.ReconDivergenceReader;
import dev.danilocordeiro.ledgermcp.adapter.synthetic.SyntheticLedger;
import java.time.Clock;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Picks where ledger data comes from: {@code ledger.mode=synthetic} (default) or {@code live}. */
@Configuration
class BackendConfiguration {

    @Configuration
    @ConditionalOnProperty(name = "ledger.mode", havingValue = "synthetic", matchIfMissing = true)
    static class Synthetic {

        @Bean
        SyntheticLedger syntheticLedger(Clock clock) {
            return new SyntheticLedger(clock);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "ledger.mode", havingValue = "live")
    static class Live {

        @Bean
        LedgerQueryClient ledgerQueryClient(LedgerMcpProperties properties) {
            return new LedgerQueryClient(restClient(properties.live().query(), "ledger.live.query.base-url"));
        }

        @Bean
        LedgerCoreClient ledgerCoreClient(LedgerMcpProperties properties, JsonMapper json) {
            return new LedgerCoreClient(restClient(properties.live().core(), "ledger.live.core.base-url"), json);
        }

        @Bean(destroyMethod = "close")
        HikariDataSource reconDataSource(LedgerMcpProperties properties) {
            LedgerMcpProperties.Recon recon = properties.live().recon();
            HikariDataSource ds = new HikariDataSource();
            ds.setPoolName("ledger-recon-readonly");
            ds.setJdbcUrl(Objects.requireNonNull(recon.jdbcUrl(), "ledger.live.recon.jdbc-url is required in live mode"));
            ds.setUsername(recon.username());
            ds.setPassword(recon.password());
            ds.setReadOnly(true);
            ds.setMaximumPoolSize(4);
            return ds;
        }

        @Bean
        ReconDivergenceReader reconDivergenceReader(HikariDataSource reconDataSource, JsonMapper json) {
            return new ReconDivergenceReader(JdbcClient.create(reconDataSource), json);
        }

        private static RestClient restClient(LedgerMcpProperties.Service service, String property) {
            if (service.baseUrl() == null || service.baseUrl().isBlank()) {
                throw new IllegalStateException(property + " is required in live mode");
            }
            return LiveHttp.restClient(service.baseUrl(), service.bearerToken(), service.timeout());
        }
    }
}
