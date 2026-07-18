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
  WEBHOOK_SECRET_ALLOW_PLAINTEXT=true \
  java -jar target/cycles-server-events-*.jar
```

## Versioning

Uses Maven CI-friendly `${revision}` property. Version is set **once** in `pom.xml`:
```xml
  <revision>0.1.25.24</revision>
```
The `flatten-maven-plugin` resolves `${revision}` at build time.

## Encryption Key

Both admin and events services must share the same `WEBHOOK_SECRET_ENCRYPTION_KEY`.
Generate with: `openssl rand -base64 32`
Empty/unset key material fails startup by default. Local development may opt out
explicitly with `WEBHOOK_SECRET_ALLOW_PLAINTEXT=true`; signing secrets are then
stored unencrypted and startup logs a warning. Existing plaintext records remain
readable after a key is configured, while new writes use `enc:`.

## Test Coverage

CI runs both the fast unit suite and the Docker-backed `integration-tests` profile. JaCoCo enforces 95%+ line and 95%+ branch coverage.

See [`AUDIT.md`](AUDIT.md) for the full source-file → test-class inventory.
