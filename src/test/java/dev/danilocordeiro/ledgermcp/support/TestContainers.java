package dev.danilocordeiro.ledgermcp.support;

import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Containers shared by every integration test class in the JVM. Started once, lazily,
 * and left for Testcontainers' reaper to remove.
 */
public final class TestContainers {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("ledger_mcp");

    /** Realm from dev/keycloak, so tests and local development use the same clients and scopes. */
    @SuppressWarnings("resource")
    public static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.4.7")
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("dev/keycloak/ledger-realm.json")),
                    "/opt/keycloak/data/import/ledger-realm.json")
            .withCommand("start-dev", "--import-realm")
            .waitingFor(Wait.forHttp("/realms/ledger/.well-known/openid-configuration")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private TestContainers() {
    }

    public static synchronized void startPostgres() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    public static synchronized void startKeycloak() {
        if (!KEYCLOAK.isRunning()) {
            KEYCLOAK.start();
        }
    }

    public static String issuerUri() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/ledger";
    }
}
