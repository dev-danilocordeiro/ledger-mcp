# Tool-selection evals

`questions.jsonl` has 20 natural-language requests, one per line. Each line names the tool a
well-behaved agent should call first, plus the arguments that tool call must include:

```json
{"id": "q14", "question": "...", "expected_tool": "propose_adjustment",
 "expected_args": {"amountMinor": 4078, "currency": "BRL"}, "confusable_with": "list_reconciliation_divergences"}
```

`confusable_with` marks the neighbouring tool the question is designed to tempt (balance vs.
entries, read vs. propose, propose vs. approve). Every one of the nine tools is the right
answer at least once; `StdioTransportIT` fails the build if a question names a tool the
server does not expose.

## Running

```bash
export ANTHROPIC_API_KEY=...        # or an `ant auth login` profile
./gradlew evals                     # default model: claude-opus-5-5
./gradlew evals -PevalModel=claude-sonnet-5-5
./gradlew evals -PdryRun        # starts the server and builds every request, sends nothing
```

The runner starts the packaged server over stdio, reads its tool list, descriptions and
instructions, and sends each question to the Messages API once with those tools. It never
executes a tool. It prints PASS/FAIL per question, writes `build/evals/report.json`, and
exits non-zero when tool accuracy falls below 90% (`--min-accuracy` to change).

Each run makes 20 API calls with nine tool definitions in context.

## Scoring

- **Tool choice**: the first `tool_use` block names `expected_tool`. A reply with no tool call is a failure.
- **Arguments**: every key in `expected_args` is present with the same value (numbers compared
  numerically, strings case-insensitively). Reported separately; it does not affect the exit code.
- A refusal counts as a failure. No fallback model is configured, because a fallback would
  score a different model than the one being evaluated.

When a question fails, change the tool description it was confused by, not the question.
