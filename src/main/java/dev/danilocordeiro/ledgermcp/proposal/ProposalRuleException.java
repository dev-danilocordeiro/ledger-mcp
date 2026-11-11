package dev.danilocordeiro.ledgermcp.proposal;

/** A proposal or approval was refused by a business rule. The message is shown to the model. */
public class ProposalRuleException extends RuntimeException {

    public ProposalRuleException(String message) {
        super(message);
    }
}
