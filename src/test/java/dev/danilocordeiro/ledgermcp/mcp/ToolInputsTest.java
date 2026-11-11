package dev.danilocordeiro.ledgermcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ToolInputsTest {

    @Test
    void dateRangesIncludeTheWholeEndDay() {
        assertThat(ToolInputs.startOf("from", "2026-10-01")).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(ToolInputs.endOf("to", "2026-10-31")).isEqualTo(Instant.parse("2026-10-31T23:59:59.999999999Z"));
        assertThat(ToolInputs.endOf("to", "2026-10-31T12:00:00Z")).isEqualTo(Instant.parse("2026-10-31T12:00:00Z"));
        assertThat(ToolInputs.startOf("from", null)).isNull();
    }

    @Test
    void errorsTellTheModelWhatToSend() {
        assertThatThrownBy(() -> ToolInputs.uuid("accountId", "Operating cash"))
                .hasMessageContaining("must be a UUID").hasMessageContaining("ask the user for the id");
        assertThatThrownBy(() -> ToolInputs.startOf("from", "31/10/2026")).hasMessageContaining("2026-10-31");
        assertThatThrownBy(() -> ToolInputs.limit(0, 50, 200)).hasMessageContaining("at least 1");
        assertThat(ToolInputs.limit(5_000, 50, 200)).isEqualTo(200);
    }
}
