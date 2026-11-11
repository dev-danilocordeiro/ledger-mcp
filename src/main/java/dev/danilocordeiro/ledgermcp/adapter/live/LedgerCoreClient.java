package dev.danilocordeiro.ledgermcp.adapter.live;

import dev.danilocordeiro.ledgermcp.domain.LedgerRejectedException;
import dev.danilocordeiro.ledgermcp.domain.PostedTransaction;
import dev.danilocordeiro.ledgermcp.domain.TransactionRequest;
import dev.danilocordeiro.ledgermcp.port.TransactionPoster;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Posts to ledger-core's {@code POST /v1/transactions}. The proposal id is the
 * Idempotency-Key, so retrying an approval can never post twice.
 */
public class LedgerCoreClient implements TransactionPoster {

    private final RestClient rest;
    private final JsonMapper json;

    public LedgerCoreClient(RestClient rest, JsonMapper json) {
        this.rest = rest;
        this.json = json;
    }

    record EntryRequest(String accountId, String direction, long amountMinorUnits) {
    }

    record PostRequest(String currency, List<EntryRequest> entries, String externalReference) {
    }

    record PostResponse(String transactionId, String postedAt, boolean replayed) {
    }

    @Override
    public PostedTransaction post(TransactionRequest request) {
        PostRequest body = new PostRequest(request.currency(),
                request.postings().stream()
                        .map(p -> new EntryRequest(p.accountId().toString(), p.direction().name(), p.amountMinor()))
                        .toList(),
                request.externalReference());
        return rest.post().uri("/v1/transactions")
                .header("Idempotency-Key", request.idempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, response) -> {
                    int status = response.getStatusCode().value();
                    if (status >= 200 && status < 300) {
                        PostResponse posted = response.bodyTo(PostResponse.class);
                        return new PostedTransaction(UUID.fromString(posted.transactionId()),
                                Instant.parse(posted.postedAt()), posted.replayed());
                    }
                    String raw = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
                    if (status >= 400 && status < 500) {
                        throw new LedgerRejectedException(problemDetail(raw, status));
                    }
                    throw new IllegalStateException("ledger-core returned HTTP " + status);
                });
    }

    /** ledger-core answers with RFC 9457 problem+json; surface its {@code detail}. */
    private String problemDetail(String raw, int status) {
        try {
            Map<?, ?> problem = json.readValue(raw, Map.class);
            Object detail = problem.get("detail");
            return detail != null ? detail.toString() : "HTTP " + status;
        } catch (RuntimeException e) {
            return "HTTP " + status;
        }
    }
}
