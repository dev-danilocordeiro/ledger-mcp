package dev.danilocordeiro.ledgermcp.evals;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Measures whether the model picks the right ledger-mcp tool for a natural-language request.
 *
 * <p>The tool list, descriptions, schemas and server instructions come from the real
 * server over stdio, so the eval scores exactly what a connected client would see. Each
 * question is one request with tool_choice auto; the first tool_use block is the answer.
 * No tool is executed. A question passes when the tool name matches; argument agreement is
 * reported separately so a wrong date range does not hide behind a right tool.
 *
 * <p>No refusal fallback model is configured on purpose: a fallback would silently score a
 * different model than the one named by --model. Refusals count as failures.
 */
public final class ToolSelectionEval {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Question(String id, String question, String expectedTool, Map<String, Object> expectedArgs, String confusableWith) {
    }

    record Result(String id, String expectedTool, String chosenTool, boolean toolCorrect, boolean argsCorrect,
                  Map<String, Object> mismatchedArgs, String stopReason, String text) {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String model = options.getOrDefault("--model", "claude-opus-5-5");
        double minAccuracy = Double.parseDouble(options.getOrDefault("--min-accuracy", "0.9"));
        List<Question> questions = load(Path.of(options.get("--questions")));

        Path home = Files.createTempDirectory("ledger-mcp-evals");
        McpSyncClient mcp = connect(Path.of(options.get("--jar")), home);
        try {
            McpSchema.InitializeResult init = mcp.initialize();
            List<McpSchema.Tool> mcpTools = mcp.listTools().tools();
            List<Tool> tools = mcpTools.stream().map(ToolSelectionEval::toAnthropicTool).toList();
            String system = """
                    You are an operations assistant for a finance team, connected to the ledger-mcp server.
                    Today is 2026-11-10. Use the server's tools to act on the user's request.

                    Server instructions:
                    """ + init.instructions();

            if (Boolean.parseBoolean(options.getOrDefault("--dry-run", "false"))) {
                for (Question q : questions) {
                    request(model, system, tools, q);
                }
                System.out.printf("dry run: %d tools converted, %d requests built for %s, nothing sent%n",
                        tools.size(), questions.size(), model);
                tools.forEach(t -> System.out.println("  " + t.name() + " " + t.inputSchema()._properties()));
                return;
            }

            AnthropicClient client = AnthropicOkHttpClient.fromEnv();
            List<Result> results = new ArrayList<>();
            for (Question q : questions) {
                Result r = ask(client, model, system, tools, q);
                results.add(r);
                System.out.printf("%-4s %-4s expected=%-32s chosen=%-32s args=%s%n", q.id(), r.toolCorrect() ? "PASS" : "FAIL",
                        q.expectedTool(), r.chosenTool(), r.argsCorrect() ? "ok" : "mismatch " + r.mismatchedArgs());
            }

            long toolHits = results.stream().filter(Result::toolCorrect).count();
            long argHits = results.stream().filter(r -> r.toolCorrect() && r.argsCorrect()).count();
            double accuracy = (double) toolHits / results.size();
            System.out.printf("%nmodel %s: tool choice %d/%d (%.0f%%), tool and arguments %d/%d%n",
                    model, toolHits, results.size(), accuracy * 100, argHits, results.size());

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("model", model);
            report.put("toolAccuracy", accuracy);
            report.put("toolAndArgsAccuracy", (double) argHits / results.size());
            report.put("results", results);
            Path reportPath = Path.of(options.getOrDefault("--report", "build/evals/report.json"));
            Files.createDirectories(reportPath.toAbsolutePath().getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
            System.out.println("report: " + reportPath.toAbsolutePath());

            if (accuracy < minAccuracy) {
                System.exit(1);
            }
        } finally {
            mcp.closeGracefully();
        }
    }

    private static Result ask(AnthropicClient client, String model, String system, List<Tool> tools, Question q) {
        Message response = client.messages().create(request(model, system, tools, q));

        String stopReason = response.stopReason().map(StopReason::toString).orElse("unknown");
        Optional<ToolUseBlock> toolUse = response.content().stream().flatMap(b -> b.toolUse().stream()).findFirst();
        String text = response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text())
                .reduce("", (a, b) -> a + b);
        if (toolUse.isEmpty()) {
            return new Result(q.id(), q.expectedTool(), "(none)", false, false, Map.of(), stopReason, abbreviate(text));
        }
        String chosen = toolUse.get().name();
        Map<String, Object> input = toMap(toolUse.get()._input());
        Map<String, Object> mismatched = new LinkedHashMap<>();
        q.expectedArgs().forEach((key, expected) -> {
            Object actual = input.get(key);
            if (actual == null || !normalize(actual).equals(normalize(expected))) {
                mismatched.put(key, actual);
            }
        });
        boolean toolCorrect = chosen.equals(q.expectedTool());
        return new Result(q.id(), q.expectedTool(), chosen, toolCorrect, toolCorrect && mismatched.isEmpty(), mismatched,
                stopReason, abbreviate(text));
    }

    private static MessageCreateParams request(String model, String system, List<Tool> tools, Question q) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(system)
                .addUserMessage(q.question());
        tools.forEach(params::addTool);
        return params.build();
    }

    /** MCP tool definition to Messages API tool definition, keeping the server's schema as-is. */
    @SuppressWarnings("unchecked")
    static Tool toAnthropicTool(McpSchema.Tool tool) {
        Map<String, Object> schema = JSON.convertValue(tool.inputSchema(), Map.class);
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> map) {
            map.forEach((name, definition) -> properties.putAdditionalProperty(name.toString(), JsonValue.from(definition)));
        }
        List<String> required = new ArrayList<>();
        if (schema.get("required") instanceof List<?> list) {
            list.forEach(r -> required.add(r.toString()));
        }
        return Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(Tool.InputSchema.builder().properties(properties.build()).required(required).build())
                .build();
    }

    private static McpSyncClient connect(Path jar, Path home) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ServerParameters server = ServerParameters.builder(java)
                .args("-jar", jar.toString(), "--spring.profiles.active=stdio")
                .addEnvVar("LEDGER_MCP_HOME", home.toString())
                .addEnvVar("LEDGER_MCP_OPERATOR", "eval-runner")
                .build();
        return McpClient.sync(new StdioClientTransport(server, McpJsonDefaults.getMapper()))
                .initializationTimeout(Duration.ofSeconds(60))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static List<Question> load(Path file) throws Exception {
        List<Question> questions = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = JSON.readTree(line);
            questions.add(new Question(node.path("id").asString(), node.path("question").asString(),
                    node.path("expected_tool").asString(),
                    JSON.convertValue(node.path("expected_args"), Map.class),
                    node.path("confusable_with").asString(null)));
        }
        return questions;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonValue value) {
        Map<String, Object> map = value.convert(Map.class);
        return map == null ? Map.of() : map;
    }

    private static String normalize(Object value) {
        String s = value.toString().trim();
        return s.matches("-?\\d+(\\.0+)?") ? s.replaceAll("\\.0+$", "") : s.toUpperCase();
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        for (String required : List.of("--jar", "--questions")) {
            if (!options.containsKey(required)) {
                throw new IllegalArgumentException("missing " + required);
            }
        }
        return options;
    }
}
