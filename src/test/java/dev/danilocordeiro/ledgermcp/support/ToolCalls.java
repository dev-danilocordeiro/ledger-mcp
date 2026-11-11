package dev.danilocordeiro.ledgermcp.support;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls a tool and returns either its JSON result or its error text. */
public final class ToolCalls {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolCalls() {
    }

    public record Outcome(boolean isError, String text) {

        public JsonNode json() {
            if (isError) {
                throw new AssertionError("tool returned an error: " + text);
            }
            return JSON.readTree(text);
        }
    }

    public static Outcome call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, arguments, null));
        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
        return new Outcome(Boolean.TRUE.equals(result.isError()), text);
    }
}
