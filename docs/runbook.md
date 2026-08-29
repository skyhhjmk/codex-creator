# Codex Creator deployment runbook

1. Back up the existing PostgreSQL volume before switching the WindBlog Compose PostgreSQL image to the pgvector-capable image.
2. The WindBlog Compose `codex-creator-db-init` service and Kubernetes `codex-creator` initContainer idempotently create the `codex_creator` database and run `CREATE EXTENSION vector`; verify that this step completes before the application starts. Extensions are database-local.
3. Set independent admin, MCP, and WindBlog HMAC secrets. Do not put raw provider keys in Git, API responses, command arguments, or logs; use a secret reference and an environment-backed secret resolver.
4. Start Codex Creator on the internal network and verify `/q/health/ready`, Liquibase, and the vector extension. Keep the service port unbound on the public host.
5. The published image already contains the pinned `codex` binary. Provide exactly one runtime credential (`OPENAI_API_KEY` or `CODEX_ACCESS_TOKEN`) through the deployment Secret, or mount a trusted `CODEX_HOME` auth cache; then enable `CODEX_APP_SERVER_ENABLED`. The entrypoint fails fast when the app-server is enabled without valid authentication. Verify `codex --version`, `initialize`, `model/list`, a fake-provider test, and a real bounded inference.
6. Configure only a manual APISIX/VPN route if an operator needs the private admin or MCP surface. Record the route, source allowlist, expiry, and revocation action.
7. Exercise WindBlog comment, link, revision, and publish events with a trace ID. Verify HMAC replay rejection after a Codex Creator restart, task idempotency, cache-lock behavior, configured provider retries, failed moderation staying pending-human-review, and AI article policy gates.

Rollback is to stop Codex Creator, disable the WindBlog adapter/default selection, revoke its HMAC/MCP/admin credentials, and restore the previous application image. Do not remove the PostgreSQL volume as a rollback step.
