package dev.danilocordeiro.ledgermcp.adapter.synthetic;

import dev.danilocordeiro.ledgermcp.domain.Direction;
import java.util.UUID;

record SyntheticAccount(UUID id, String name, String currency, Direction normalSide, boolean allowsNegative) {
}
