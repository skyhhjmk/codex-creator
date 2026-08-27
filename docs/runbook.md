# Codex Creator deployment runbook

1. Back up the existing PostgreSQL volume before switching the WindBlog Compose PostgreSQL image to the pgvector-capable image.
2. Create the `codex_creator` database and grant only the service role access to it. Run `CREATE EXTENSION vector` in that database; extensions are database-local.
3. Set independent admin, MCP, and WindBlog HMAC secrets. Do not put raw provider keys in Git, API responses, command arguments, or logs; use a secret reference and an environment-backed secret resolver.
4. Start Codex Creator on the internal network and verify `/q/health/ready`, Liquibase, and the vector extension. Keep the service port unbound on the public host.
5. Install and authenticate the intended `codex` binary, then enable `CODEX_APP_SERVER_ENABLED`. Verify `initialize`, `model/list`, a fake-provider test, and a real bounded inference.
6. Configure only a manual APISIX/VPN route if an operator needs the private admin or MCP surface. Record the route, source allowlist, expiry, and revocation action.
7. Exercise WindBlog comment, link, revision, and publish events with a trace ID. Verify HMAC replay rejection, task idempotency, failed moderation staying pending-human-review, and AI article policy gates.

Rollback is to stop Codex Creator, disable the WindBlog adapter/default selection, revoke its HMAC/MCP/admin credentials, and restore the previous application image. Do not remove the PostgreSQL volume as a rollback step.
