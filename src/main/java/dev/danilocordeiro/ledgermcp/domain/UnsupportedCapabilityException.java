package dev.danilocordeiro.ledgermcp.domain;

/**
 * The configured backend cannot answer this request. The message is written for the
 * model: it says what is missing so the agent can tell the user instead of retrying.
 */
public class UnsupportedCapabilityException extends RuntimeException {

    public UnsupportedCapabilityException(String message) {
        super(message);
    }
}
