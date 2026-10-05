# API gateway

Build from the reactor root with dependencies included:

```powershell
.\mvnw.cmd -pl services/api-gateway -am package
java -jar services/api-gateway/target/api-gateway.jar --server.port=8080
```

The gateway uses stateless HTTP Basic authentication. Configure the same
credentials as the downstream services: `COMPILE_USER` / `COMPILE_PASSWORD`,
`ARCHFORGE_SECURITY_USER` / `ARCHFORGE_SECURITY_PASSWORD`, and optionally
`ADMIN_USER` / `ADMIN_PASSWORD` for the gateway operator, or
`ARCHFORGE_SECURITY_DESIGN_ADMIN_USER` / `ARCHFORGE_SECURITY_DESIGN_ADMIN_PASSWORD`
for an administrator of the design service. No accounts exist when these are empty.
Partial credential pairs and conflicting passwords for the same username are
rejected at startup. Use TLS at the public ingress for Basic credentials.

The gateway administrator is `archforge.security.operator-admin-user`, **not**
`archforge.security.admin-user`. Spring resolves the key
`archforge.security.admin-user` from the environment variable
`ARCHFORGE_SECURITY_ADMIN_USER`, which outranks the `${ADMIN_USER:}` placeholder:
declaring `admin-user: ${ADMIN_USER:}` meant that exporting both variables silently
removed the `ADMIN_USER` credential and every administrator request returned 401 with
nothing logged. The property name is now disjoint from the design-service
administrator's, and startup fails loudly if `archforge.security.admin-user` is
configured again by any means.

`Authorization` is forwarded unchanged. `X-User-Id` is removed; rate-limit keys
come from the authenticated principal. This preserves the downstream Basic
principal used for design/submission ownership. There is no authentication-off
mode at the gateway.

Java `GatewayConfig` owns all routes. Every route has a principal-based Redis
limiter and circuit breaker. Fallbacks retain the request method and return 503.
`GATEWAY_REQUEST_TIMEOUT` defaults to `30s`, covering the downstream submission's
15-second Kafka publish budget and cold database initialization. The framework's
implicit one-second deadline returned 503 even when a write subsequently committed.
A timeout is still an ambiguous write outcome: reload before retrying a save/create.

| Route ID | Paths | Target variable (default) | Responsibility |
| --- | --- | --- | --- |
| graph-compiler | `/api/v1/compile/**` | `GRAPH_COMPILER_URL` (`http://localhost:8081`) | Graph validation and compilation. |
| canvas-session | `/api/v1/session/**` | `CANVAS_SESSION_URL` (`http://localhost:8086`) | Live collaboration session state; not persisted designs. |
| ai-evaluator | `/api/v1/evaluate/**` | `AI_EVALUATOR_URL` (`http://localhost:8084`) | Direct evaluation requests. |
| design-service | `/api/v1/designs/**`, `/api/v1/submissions/**` | `DESIGN_SERVICE_URL` (`http://localhost:8085`) | Persisted designs, conditional topology saves, submissions, measurements, and evaluations. |
| graph-compiler-admin | `/api/v1/admin/**` | `GRAPH_COMPILER_URL` (`http://localhost:8081`) | Compiler administration. |
| websocket | `/ws/**` | `CANVAS_WEBSOCKET_URL` (`ws://localhost:8086`) | Canvas-session owns the `/ws/canvas` WebSocket handler. |

Canvas-session previously defaulted to 8085, colliding with design-service. Its
default is now 8086; design-service retains 8085. Both still accept `SERVER_PORT`.
Override the route URI variables above when deploying to different hosts/ports.
Canvas-session uses ArchForge's Valkey default 16379, rather than another project's
Redis on 6379. The session REST route can only serve endpoints implemented by
canvas-session; routing alone does not create REST handlers.

`GATEWAY_ALLOWED_ORIGINS` is a comma-separated list of exact HTTP(S) origins,
defaulting to `http://localhost:3000`. Wildcards are rejected. CORS supports
explicit Authorization and If-Match headers without cookie credentials; ETag is
exposed to browser clients. Topology saves require a quoted revision in If-Match
and return 409 with `storedRevision` and `reloadRequired` on conflicts.

Unauthenticated GET health/probe requests are permitted. All other actuator
reads require an administrator, including `/actuator/gateway/routes`; actuator
mutations are denied. Health component details also require an administrator.

`GATEWAY_REQUESTS_PER_MINUTE` defaults to 60 and `GATEWAY_BURST_CAPACITY` to 10
requests per principal per route. Redis uses tokens per second: each request
costs 60 tokens, replenishment equals the RPM value, and capacity equals burst
requests times 60. This is a token bucket with a sustained rate and burst, not
a fixed calendar-minute window. Redis defaults to ArchForge's Valkey port
16379; `ARCHFORGE_VALKEY_HOST` / `ARCHFORGE_VALKEY_PORT` (or `REDIS_HOST` /
`REDIS_PORT`) override it. Spring Cloud Gateway's Redis limiter permits requests
when Redis is unavailable; monitor Redis health if strict enforcement is needed.

The gateway does not publish or consume Kafka messages. The shared messaging
dependency supplies fleet wiring and `KafkaTopics`; no topic names or `NewTopic`
beans are defined here. Kafka bootstrap/group settings satisfy that shared wiring
without requiring a broker connection for gateway startup.
