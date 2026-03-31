## Git Rules — STRICT
- ALWAYS use native git for ALL commits and pushes
- NEVER use mcp__github__ tools for committing or pushing
- Write commit messages to a temp file, then: `git commit -F <file>`
- NEVER use --no-gpg-sign flag

# Cycles Server Events

## Build
```bash
mvn verify
mvn verify -Dtest='!*IntegrationTest'  # Without Docker
```

## Run
```bash
REDIS_HOST=localhost REDIS_PORT=6379 java -jar target/cycles-server-events-0.1.0.jar
```
