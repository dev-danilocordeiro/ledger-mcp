package dev.danilocordeiro.ledgermcp.adapter.live;

import java.time.Duration;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the sibling services. Plain HTTP/1.1 on purpose: the JDK client's
 * default cleartext HTTP/2 upgrade is not something every service in front of the ledger
 * supports, and a POST cancelled mid-upgrade leaves the posting's outcome unknown.
 */
public final class LiveHttp {

    private LiveHttp() {
    }

    public static RestClient restClient(String baseUrl, String bearerToken, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(Objects.requireNonNull(baseUrl, "baseUrl"))
                .requestFactory(factory);
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        return builder.build();
    }
}
