# AI-Sentinel ASP.NET Core Reference Adapter

The ASP.NET Core adapter is a **client/integration layer** for the AI-Sentinel remote evaluation service. It does **not** contain or reimplement the behavioral-risk engine.

## What it is

- Thin ASP.NET Core middleware and HTTP client for the frozen Step-8 evaluation contract
- Consumes the Step-9 remote evaluation API (`POST /ai-sentinel/v1/evaluation`)
- Maps trusted `HttpContext.User` identity and request context into `EvaluationRequest`
- Applies server-authoritative `proceed` and enforcement actions from `EvaluationResponse`

## What it is not

- Not a C# port of Welford scoring, Isolation Forest, baselines, quarantine state, or policy fusion
- Not a local fallback engine (Java may optionally fall back to an in-process engine; this adapter cannot)
- Not end-user authentication, MFA, OAuth/OIDC, mTLS, WAF, or SIEM
- Not production ENFORCE guidance — adopt **MONITOR-first**

## Architecture

```text
ASP.NET Core host (authenticates user)
        │
        ▼
AI.Sentinel.AspNetCore middleware
        │
        ▼
HTTP/JSON + X-AI-Sentinel-Api-Key
        │
        ▼
AI-Sentinel remote evaluation service (Java)
        │
        ▼
Authoritative AI-Sentinel engine
```

## Prerequisites

- .NET SDK 8.0 (LTS)
- Running AI-Sentinel remote evaluation service (Java/Spring **0.3.0** tree or compatible release)

Pair with the Java starter or service using remote evaluation enabled. Upgrade and contract notes: [`../docs/migration.md`](../docs/migration.md).

## Build

```bash
cd dotnet
dotnet restore
dotnet build
dotnet test
```

Shared wire fixtures live in `dotnet/fixtures/` (`requests/` and `responses/`) and are validated by both Java and .NET tests.

## Testing

```bash
cd dotnet
dotnet test
```

Most tests use in-memory HTTP stubs. For a **live cross-runtime** check against a running Java evaluation service, set:

```bash
export AI_SENTINEL_E2E_SERVICE_URL=http://127.0.0.1:8080
export AI_SENTINEL_E2E_API_KEY=your-test-key
dotnet test --filter "FullyQualifiedName~LiveCrossRuntimeE2ETests"
```

The Java host must have `ai.sentinel.evaluation.server.enabled=true` and a matching API key. When the environment variables are unset, the live E2E test exits without failure (opt-in).

## Installation

Reference the library project or package:

```xml
<ProjectReference Include="path/to/AI.Sentinel.AspNetCore.csproj" />
```

Register in `Program.cs`:

```csharp
builder.Services.AddAuthentication(...); // host responsibility
builder.Services.AddAuthorization();
builder.Services.AddAiSentinel(builder.Configuration);

app.UseAuthentication();
app.UseAuthorization();
app.UseAiSentinel(); // after authentication populates HttpContext.User
```

## Middleware pipeline order

The adapter reads trusted identity from `HttpContext.User`. Register middleware **after** host authentication/authorization middleware when both are used:

```text
UseAuthentication()
    ↓
UseAuthorization()      // optional; required for [Authorize] / RequireAuthorization()
    ↓
UseAiSentinel()
    ↓
MapEndpoints()
```

AI-Sentinel does **not** authenticate end users. It only consumes the principal established by the host.

## Configuration (`AiSentinel` section)

| Setting | Description |
|---------|-------------|
| `Enabled` | When `false`, middleware is a no-op |
| `ServiceUrl` | Remote service base URL |
| `ApiKey` | Sent as `X-AI-Sentinel-Api-Key` (never logged) |
| `EvaluationPath` | Default `/ai-sentinel/v1/evaluation` |
| `ConnectTimeoutMilliseconds` | TCP connect timeout |
| `ReadTimeoutMilliseconds` | HTTP read timeout |
| `IdentityClaimType` | Claim used for `identityKey` (default NameIdentifier) |
| `DenyStatusCode` | HTTP status when `proceed=false` (default 429) |
| `RequireHttps` | Rejects non-HTTPS URLs except loopback HTTP for tests |
| `IncludeSafeHeaders` | Includes only a small allowlist of bounded non-sensitive headers; excludes credentials, token-like headers, and raw forwarding/proxy identity headers |

Server-owned settings (thresholds, MONITOR/ENFORCE mode, baselines, quarantine) remain on the Java service.

## MONITOR-first deployment

1. Integrate middleware with `Enabled=true` while the server runs in MONITOR posture
2. Observe decisions via logs/metrics
3. Tune server policy using real traffic
4. Only then consider enforcement

The sample app (`samples/AI.Sentinel.AspNetCore.Sample`) demonstrates MONITOR-first configuration against a local Java service.

## Identity mapping

- Authenticated `HttpContext.User` → `identityKey` from configured claim (default `NameIdentifier`)
- Missing authentication → `identityType=ANONYMOUS`, blank `identityKey` (contract-supported; see limitation below)
- Client-supplied identity headers are **not** trusted by default
- `remoteAddress` comes from `HttpContext.Connection.RemoteIpAddress`; configure ASP.NET Core `ForwardedHeadersMiddleware` with trusted proxies if the host needs proxy-derived client IPs. Raw `X-Forwarded-*`, `Forwarded`, and `X-Real-IP` request headers are not forwarded as contract metadata.

**Anonymous limitation:** All unauthenticated requests share the same empty `identityKey` with `identityType=ANONYMOUS`. The Java engine may therefore treat them as one anonymous behavioral subject. Host applications should authenticate users before sensitive endpoints, or disable the adapter for routes that must not share anonymous baselines.

## Failure behavior

Transport/client failures produce a single synthetic `REMOTE_EVALUATION_FAILURE` response:

- `action=ALLOW`, `proceed=true`
- No fabricated BLOCK/QUARANTINE/THROTTLE or maximum risk scores
- One HTTP attempt per evaluation (no automatic retry)

## Action handling

| Action | Adapter behavior |
|--------|------------------|
| ALLOW / MONITOR | Continue pipeline when `proceed=true` |
| THROTTLE / BLOCK / QUARANTINE | Deny with configured status when `proceed=false` |
| REMOTE_EVALUATION_FAILURE | Fail-open continue |

`proceed` from the server is authoritative; the adapter validates action/proceed consistency.

## Observability

OpenTelemetry-style meters under `AI.Sentinel.AspNetCore` with bounded labels (`action`, `outcome`). High-cardinality values (user id, correlation id, raw paths) are not used as metric dimensions.

## Known limitations

- No local Java-style fallback when remote service is unavailable
- No OAuth/mTLS for API key transport in this reference increment
- THROTTLE is not implemented as an artificial delay; enforcement maps to HTTP denial when `proceed=false`
- Remote cancellation is not guaranteed once the HTTP request is in flight

## Sample

```bash
# Terminal 1: start Java AI-Sentinel with remote evaluation enabled
# Terminal 2:
cd dotnet/samples/AI.Sentinel.AspNetCore.Sample
dotnet run
```

Set `AiSentinel:ApiKey` via environment or user secrets — never commit secrets.
