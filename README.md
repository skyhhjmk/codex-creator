# Codex Creator

`Codex Creator` is WindBlog's private AI orchestration service. It is an independent Java 21 / Quarkus repository and uses a separate `codex_creator` PostgreSQL database. The service never reads or writes WindBlog tables directly.

## Runtime boundary

- The default Codex integration owns one local `codex app-server` child process and speaks newline-delimited JSON-RPC over stdio.
- Every app-server connection sends `initialize`, waits for the response, and then sends `initialized`. Experimental API capability is disabled by default.
- Runtime calls use `/api/v1/runtime/infer` and persist an idempotent task, attempts, provider usage, provenance, and audit records.
- WindBlog calls the internal event endpoint with the versioned HMAC timestamp/nonce/body-digest contract. Admin and MCP credentials are separate.
- `/mcp` exposes only the allowlisted tool names in `McpResource`; generic SQL, shell, and arbitrary HTTP tools do not exist. Write tools require explicit approval.
- OpenAPI and Swagger are private by default. Any APISIX/VPN publication is an operator-owned, reversible deployment change.

The app-server protocol facts follow the [official Codex App Server documentation](https://learn.chatgpt.com/docs/app-server). The provider adapter's Responses payload follows the [official Responses API reference](https://developers.openai.com/api/reference/cli/resources/responses/methods/create). MCP configuration and bearer-token behavior follow [official MCP documentation](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).

## Local development

```bash
cp .env.example .env
./mvnw test
./mvnw package -DskipTests
docker compose --env-file .env up --build
```

The default application does not start Codex until `CODEX_APP_SERVER_ENABLED=true` is explicitly set. The installed `codex` binary, authentication, model availability, account quota, MCP integration, HTTPS, backups, and target ingress remain deployment acceptance items.

## API outline

| Surface | Path | Default access |
|---|---|---|
| Health | `/q/health/ready` | internal deployment probe |
| Runtime | `/api/v1/runtime/infer`, `/api/v1/runtime/tasks` | WindBlog signed service call |
| Admin | `/api/admin/*` | private bearer token |
| MCP | `/mcp` | private bearer token, allowlisted tools |
| WindBlog events | `/api/internal/integrations/windblog/events` | HMAC service signature |

## Verification status

JVM compilation and protocol/policy unit tests can prove the local implementation. They do not prove a real Codex login, target-network access, pgvector migration on an existing volume, APISIX exposure, or a complete Compose E2E chain; those require a separately authorized target-environment runbook.
