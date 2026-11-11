package dev.danilocordeiro.ledgermcp.proposal;

import dev.danilocordeiro.ledgermcp.domain.Posting;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * A confirmation code is a digest of everything the proposal would post. Approving with
 * it proves the approver saw this exact content: if the stored row were edited after the
 * proposal was shown, the recomputed code would no longer match.
 *
 * <p>It is not a secret. The agent sees it too. Separation of duties comes from scopes and
 * the four-eyes rule in {@link ProposalService}, not from this code.
 */
final class ConfirmationCodes {

    private ConfirmationCodes() {
    }

    static String compute(UUID id, ProposalKind kind, String currency, String externalReference, List<Posting> postings) {
        StringBuilder canonical = new StringBuilder()
                .append(id).append('|').append(kind).append('|').append(currency).append('|')
                .append(externalReference);
        for (Posting p : postings) {
            canonical.append('|').append(p.accountId()).append(':').append(p.direction()).append(':').append(p.amountMinor());
        }
        String hex = HexFormat.of().withUpperCase().formatHex(sha256(canonical.toString())).substring(0, 8);
        return hex.substring(0, 4) + "-" + hex.substring(4);
    }

    static boolean matches(String expected, String provided) {
        if (provided == null) {
            return false;
        }
        String normalized = provided.trim().toUpperCase();
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), normalized.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
