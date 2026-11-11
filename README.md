# ledger-mcp

An MCP server that exposes a double-entry ledger to AI agents. Agents can read balances,
statements and bank-reconciliation divergences. They can also **propose** reversals and
adjustments. A proposal changes nothing until a different person with the right scope approves it
in a second call.

Java 21, Spring Boot 4.1, Spring AI 2.0 (MCP Java SDK 2.1). It runs over two transports:

| Transport | For | Identity | Proposals and audit stored in |
|---|---|---|---|
| Streamable HTTP at `/mcp` | shared or remote use | OAuth 2 bearer JWT, scopes per tool | PostgreSQL |
| stdio | a single person's local client | the OS user, or `LEDGER_MCP_OPERATOR` | H2 file in `~/.ledger-mcp` |

Every account, transaction, reference and name is synthetic. The data does not describe any real
company or person.

## Tools

| Tool | Scope | What it does |
|---|---|---|
| `get_account_balance` | `ledger:read` | Current balance of one account |
| `list_account_entries` | `ledger:read` | Postings on one account in a date range, paged |
| `get_transaction` | `ledger:read` | All postings of one transaction |
| `list_reconciliation_divergences` | `recon:read` | Ledger vs. bank (CNAB240) disagreements, by kind and date |
| `propose_reversal` | `ledger:propose` | Drafts a mirror transaction. **Posts nothing** |
| `propose_adjustment` | `ledger:propose` | Drafts a two-legged correction. **Posts nothing** |
| `get_proposal` | `ledger:propose` | Status and content of a proposal |
| `approve_proposal` | `ledger:approve` | Executes a pending proposal |
| `reject_proposal` | `ledger:approve` | Closes a pending proposal without posting |

Each tool description is written for the model. It says when to use the tool, gives an example
call, and says when *not* to use it, naming the sibling tool to use instead. The read tools carry
`readOnlyHint`, and `approve_proposal` is the only tool marked `destructiveHint`.

### How a write happens

```
agent:     propose_adjustment(...)       -> proposal PENDING, confirmation code 7A3F-19C2, expires in 15 min
           (shows it to the user and stops)
approver:  approve_proposal(id, "7A3F-19C2")
server:    requires all of: ledger:approve scope, approvals enabled on this transport,
           still PENDING and unexpired, code matches the stored content,
           approver != proposer
           -> posts once, using the proposal id as the ledger's Idempotency-Key
           -> EXECUTED with the ledger transaction id (or FAILED with the ledger's reason)
```

- **Human approval comes from the scopes and the four-eyes rule.** The confirmation code is
  not a secret; the agent sees it too. The code is a digest of the postings, so an approval
  applies to the exact content that was shown. A row edited after it was proposed is refused.
- **Repeated approvals post once.** Approving twice returns the same transaction. If the
  call to the ledger fails with an unknown outcome, the proposal stays `EXECUTING`, and
  approving again is safe because the ledger deduplicates on the proposal id.
- **stdio cannot approve.** The local operator gets `ledger:read`, `recon:read` and
  `ledger:propose` by default, and `ledger.proposals.approvals-enabled` is `false` on stdio.
  Approval happens over HTTP, by a second identity.

### Audit trail

Every tool call writes two rows to `audit_event`. A `STARTED` row is written before the tool
runs, so if the audit cannot be written, the call is refused. A `COMPLETED` row records the
outcome: `OK`, `ERROR`, or `DENIED` when a scope was missing. Both rows record:

- the principal, OAuth client and transport
- the tool and its arguments as JSON
- the result (truncated at 16 KB) or the error
- the duration

The table is append-only at the database level: PostgreSQL triggers reject `UPDATE`, `DELETE`
and `TRUNCATE`, and H2 triggers reject `UPDATE` and `DELETE`.

```sql
SELECT occurred_at, principal, tool, outcome, arguments, error
  FROM audit_event WHERE phase = 'COMPLETED' ORDER BY id DESC LIMIT 20;
```

The audit is written by one aspect (`ToolCallAuditor`) around every `@McpTool` method. The same
aspect enforces `@RequiredScope`, so a tool without a declared scope is refused.

## Data sources

`ledger.mode` selects the backend:

- **`synthetic`** (default): an in-memory ledger generated from a fixed seed. It covers
  10 accounts, 269 transactions from 2026-09-01 to 2026-11-09, and 11 divergences of all
  three kinds. Approved proposals really post to it, until the process exits.
- **`live`**: the sibling services.
  - Balances and statements come from **ledger-query** (`GET /v1/accounts/{id}/balance` and
    `/statement?from&to&limit&cursor`).
  - Approved proposals are posted to **ledger-core** (`POST /v1/transactions` with
    `Idempotency-Key`).
  - Divergences are read from **ledger-recon**'s `reconciliation_result` table through a
    read-only connection, because ledger-recon has no HTTP API for them.

  `get_transaction` and `propose_reversal` are **not available in live mode**: ledger-query has
  no lookup by transaction id. Those tools answer with an error that says so and points the
  agent to `list_account_entries` or `propose_adjustment`.

Synthetic account ids follow the pattern `a0000000-0000-4000-8000-0000000000NN`:

| NN | Account | Currency | Normal side |
|---|---|---|---|
| 01 | Operating cash | BRL | debit |
| 02 | Customer funds payable | BRL | credit |
| 03 | Card settlement clearing | BRL | debit |
| 04 | Fee revenue | BRL | credit |
| 05 | Suspense | BRL | debit |
| 06 | Merchant 0042 wallet | BRL | credit |
| 07 | Merchant 0117 wallet | BRL | credit |
| 08 | Owner's equity | BRL | credit |
| 09 | USD operating cash | USD | debit |
| 10 | USD owner's equity | USD | credit |

Transaction ids are `b0000000-0000-4000-8000-000000000001` and upward, in posting order.

## Build and test

```bash
./gradlew build          # compile, unit and integration tests (needs Docker), boot jar
```

The integration tests use Testcontainers:

- **`HttpTransportIT`**: PostgreSQL, Keycloak with the realm in `dev/keycloak`, and the MCP Java
  SDK client over Streamable HTTP. It covers the 401 challenge and resource metadata, scope
  denial, the full propose/approve flow, wrong codes, the four-eyes rule, duplicate reversals,
  and append-only enforcement.
- **`LiveAdaptersIT`**: WireMock speaking ledger-query's and ledger-core's contracts, and
  PostgreSQL holding ledger-recon's table.
- **`StdioTransportIT`**: launches the packaged jar as a child process, the way Claude Desktop
  does.

## Run it locally (stdio)

```bash
./gradlew bootJar
java -jar build/libs/ledger-mcp-0.1.0-SNAPSHOT.jar --spring.profiles.active=stdio
```

Nothing but JSON-RPC is written to stdout. Logs go to `~/.ledger-mcp/ledger-mcp.log`.

### Claude Code

```bash
claude mcp add --transport stdio --env LEDGER_MCP_OPERATOR=alice ledger \
  -- java -jar /absolute/path/to/ledger-mcp/build/libs/ledger-mcp-0.1.0-SNAPSHOT.jar --spring.profiles.active=stdio
```

Add `--scope user` to make it available in every project, or `--scope project` to write it to
`.mcp.json` for the team. Check it with `/mcp` inside Claude Code.

### Claude Desktop

Open **Settings → Developer → Edit Config** and add the server to `claude_desktop_config.json`.
The file is in `~/Library/Application Support/Claude/` on macOS, `%APPDATA%\Claude\` on Windows,
and `~/.config/Claude/` on Linux.

```json
{
  "mcpServers": {
    "ledger": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/ledger-mcp/build/libs/ledger-mcp-0.1.0-SNAPSHOT.jar",
        "--spring.profiles.active=stdio"
      ],
      "env": {
        "LEDGER_MCP_OPERATOR": "alice"
      }
    }
  }
}
```

Fully quit and reopen Claude Desktop. If `java` is not on the PATH that Claude Desktop sees,
use the absolute path to the Java 21+ binary. Connection errors are written to the `mcp*.log`
files in Claude Desktop's log folder (`~/Library/Logs/Claude` on macOS).

## Run it over HTTP

```bash
docker compose -f dev/docker-compose.yml up -d     # PostgreSQL :5433, Keycloak :8180
java -jar build/libs/ledger-mcp-0.1.0-SNAPSHOT.jar  # profile "http" is the default; listens on :8085
```

The dev realm (`dev/keycloak/ledger-realm.json`) defines these demo clients. The secrets are
for local use only.

| Client | Grant | Scopes |
|---|---|---|
| `ledger-reader` / `reader-secret` | client credentials | read, recon |
| `ledger-agent` / `agent-secret` | client credentials | read, recon, propose |
| `ledger-approver` / `approver-secret` | client credentials | read, recon, approve |
| `ledger-supervisor` / `supervisor-secret` | client credentials | all four. Use it to see the four-eyes rule refuse self-approval |
| `claude-code-analyst` (public, PKCE, callback port 8095) | authorization code | read, recon, propose |
| `claude-code-approver` (public, PKCE, callback port 8096) | authorization code | read, recon, approve |

Demo users for the authorization-code clients: `analyst` / `analyst` and `approver` / `approver`.

### Claude Code, with interactive OAuth login

The server publishes OAuth Protected Resource Metadata, and its 401 challenge points Claude Code
to Keycloak. Register the server with the pre-registered public client and its fixed callback
port, then run `/mcp` inside Claude Code and log in as `analyst` in the browser:

```bash
claude mcp add --transport http --client-id claude-code-analyst --callback-port 8095 \
  ledger http://localhost:8085/mcp
```

To test the full four-eyes flow:

1. In a second Claude Code session, register the server with
   `--client-id claude-code-approver --callback-port 8096` and log in as `approver`.
2. Have the analyst session propose a change.
3. Have the approver session approve it.

The test suite checks that the realm accepts these authorization requests. It does not drive the
browser step.

### Claude Code, with a bearer token

For scripts, or when you don't want a browser step, pass a token directly. Demo tokens last
15 minutes.

```bash
claude mcp add --transport http ledger http://localhost:8085/mcp \
  --header "Authorization: Bearer $(dev/token.sh ledger-agent agent-secret)"
```

### Claude Desktop over HTTP

Claude Desktop connects to remote servers as custom connectors (**Settings → Connectors**), and
those connections are made from Anthropic's infrastructure, not from your machine. So
`localhost:8085` will not work. Deploy the server behind a public HTTPS URL, with an authorization
server that Claude can reach. For local work with Claude Desktop, use stdio as shown above.

## Configuration

| Property / env var | Default | Meaning |
|---|---|---|
| `ledger.mode` / `LEDGER_MCP_MODE` | `synthetic` | `synthetic` or `live` |
| `ledger.proposals.ttl` | `15m` | How long a proposal can be approved |
| `ledger.proposals.approvals-enabled` | `true` (`false` on stdio) | Whether `approve_proposal` may execute |
| `ledger.stdio.operator` / `LEDGER_MCP_OPERATOR` | OS user | Principal recorded for stdio calls |
| `ledger.stdio.scopes` | read, recon, propose | Scopes granted to the stdio operator |
| `LEDGER_MCP_ISSUER_URI` | `http://localhost:8180/realms/ledger` | JWT issuer for HTTP |
| `ledger.http.validate-audience` | `false` | Require the token's `aud` to be this server |
| `LEDGER_MCP_DB_URL`, `_USER`, `_PASSWORD` | local compose | PostgreSQL for proposals and audit |
| `LEDGER_MCP_HOME` | `~/.ledger-mcp` | H2 database and log file for stdio |
| `LEDGER_QUERY_URL`, `LEDGER_QUERY_TOKEN` | none | ledger-query base URL and optional bearer token (live) |
| `LEDGER_CORE_URL`, `LEDGER_CORE_TOKEN` | none | ledger-core base URL and optional bearer token (live) |
| `LEDGER_RECON_JDBC_URL`, `_DB_USER`, `_DB_PASSWORD` | none | ledger-recon database, read-only role (live) |

## Evals

`evals/questions.jsonl` holds 20 natural-language requests, each with the tool a good agent
should call first. They are designed around the tool pairs that are easy to confuse. See
[`evals/README.md`](evals/README.md).

```bash
ANTHROPIC_API_KEY=... ./gradlew evals                 # default model: claude-opus-5-5
./gradlew evals -PevalModel=claude-sonnet-5-5
./gradlew evals -PdryRun                              # builds every request, sends nothing
```

## Known limitations

- **Duplicated error text.** Spring AI 2.0.2 repeats a tool's error message (wrapper message
  plus cause message) in the text returned to the model. It is harmless, and it comes from the
  framework's callback, not from this code.
- **Not on the newest MCP spec.** The server speaks MCP 2025-11-25, the version the Java SDK 2.1
  line implements. Support for the stateless 2026-07-28 spec is only in SDK 2.2 milestones.
- **mcp-server-security is a community project.** Spring's documentation marks it as work in
  progress. It is pinned at 0.1.14, the line that matches Spring AI 2.0.x.
- **Live mode has no transaction lookup or reversal.** This waits on ledger-query exposing a
  transaction lookup endpoint.
