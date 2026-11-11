package dev.danilocordeiro.ledgermcp.support;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

/** Client-credentials tokens for the demo clients defined in dev/keycloak/ledger-realm.json. */
public enum Tokens {
    READER("ledger-reader", "reader-secret"),
    AGENT("ledger-agent", "agent-secret"),
    APPROVER("ledger-approver", "approver-secret"),
    SUPERVISOR("ledger-supervisor", "supervisor-secret");

    private final String clientId;
    private final String secret;

    Tokens(String clientId, String secret) {
        this.clientId = clientId;
        this.secret = secret;
    }

    /** The principal the server records for this client: Keycloak's service-account user name. */
    public String principal() {
        return "service-account-" + clientId;
    }

    public String fetch() {
        String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(secret);
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(TestContainers.issuerUri() + "/protocol/openid-connect/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("token request failed: " + response.statusCode() + " " + response.body());
            }
            return JsonMapper.builder().build().readTree(response.body()).path("access_token").asString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
