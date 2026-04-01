## Git Rules — STRICT
- ALWAYS use native git for ALL commits and pushes
- NEVER use mcp__github__ tools for committing or pushing
- Write commit messages to a temp file, then: `git commit -F <file>`
- NEVER use --no-gpg-sign flag

# Cycles strict rules
- yaml API specs always the authority
- always update AUDIT.md files when making changes to server, admin, client repos
- maintain at least 95% or higher test coverage for all code repos

# Cycles Server Events

## Maven Builds

In Claude Code remote environments, use `mvn-proxy` instead of `mvn` for all Maven commands.

```bash
mvn-proxy -B verify        # Full build with tests + coverage
mvn-proxy -B package -DskipTests  # Build JAR only
```

## Run

```bash
REDIS_HOST=localhost REDIS_PORT=6379 REDIS_PASSWORD="" \
  WEBHOOK_SECRET_ENCRYPTION_KEY="" \
  java -jar target/cycles-server-events-0.1.0.jar
```

## Encryption Key

Both admin and events services must share the same `WEBHOOK_SECRET_ENCRYPTION_KEY`.
Generate with: `openssl rand -base64 32`
If empty/unset, signing secrets are stored and read as plaintext (backward compatible).

## Test Coverage

102 tests, 95%+ line coverage enforced via JaCoCo.
