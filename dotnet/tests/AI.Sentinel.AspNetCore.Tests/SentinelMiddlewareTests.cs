using System.Net;
using System.Security.Claims;
using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Mapping;
using AI.Sentinel.AspNetCore.Middleware;
using AI.Sentinel.AspNetCore.Options;
using AI.Sentinel.AspNetCore.Remote;
using AI.Sentinel.AspNetCore.Tests.Support;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace AI.Sentinel.AspNetCore.Tests;

public class SentinelMiddlewareTests
{
    [Fact]
    public async Task DisabledMiddlewarePassesThrough()
    {
        await using var host = await CreateHostAsync(
            enabled: false,
            _ => Task.FromResult(FixturePaths.ReadResponseFixture("block.json")));

        var response = await host.GetTestClient().GetAsync("/api/hello");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task AllowProceedsToEndpoint()
    {
        await using var host = await CreateHostAsync(
            enabled: true,
            _ => Task.FromResult(FixturePaths.ReadResponseFixture("allow.json")));

        var response = await host.GetTestClient().GetAsync("/api/hello");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task MonitorProceedsToEndpoint()
    {
        await using var host = await CreateHostAsync(
            enabled: true,
            _ => Task.FromResult(FixturePaths.ReadResponseFixture("monitor.json")));

        var response = await host.GetTestClient().GetAsync("/api/hello");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task BlockReturnsDenyStatus()
    {
        await using var host = await CreateHostAsync(
            enabled: true,
            _ => Task.FromResult(FixturePaths.ReadResponseFixture("block.json")));

        var response = await host.GetTestClient().GetAsync("/api/hello");
        Assert.Equal((HttpStatusCode)429, response.StatusCode);
    }

    [Fact]
    public async Task QuarantineReturnsDenyStatus()
    {
        await using var host = await CreateHostAsync(
            enabled: true,
            _ => Task.FromResult(FixturePaths.ReadResponseFixture("quarantine.json")));

        var response = await host.GetTestClient().GetAsync("/api/hello");
        Assert.Equal((HttpStatusCode)429, response.StatusCode);
    }

    [Fact]
    public async Task RemoteFailureFailOpenProceeds()
    {
        await using var host = await CreateHostAsync(
            enabled: true,
            _ => Task.FromResult(EvaluationFailureResponses.RemoteFailure("remote-fail")));

        var response = await host.GetTestClient().GetAsync("/api/hello");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task ResponseStoredOnHttpContextItems()
    {
        EvaluationResponse? captured = null;
        await using var host = await CreateHostAsync(
            enabled: true,
            _ => Task.FromResult(FixturePaths.ReadResponseFixture("monitor.json")),
            app =>
            {
                app.Use(async (context, next) =>
                {
                    await next();
                    captured = context.Items[SentinelMiddleware.ResponseItemKey] as EvaluationResponse;
                });
            });

        await host.GetTestClient().GetAsync("/api/hello");
        Assert.NotNull(captured);
        Assert.Equal(EnforcementAction.MONITOR, captured!.Action);
    }

    [Fact]
    public async Task AuthenticatedPrincipalMapsToIdentityKey()
    {
        EvaluationRequest? capturedRequest = null;
        await using var host = await CreateHostAsync(
            enabled: true,
            request =>
            {
                capturedRequest = request;
                return Task.FromResult(FixturePaths.ReadResponseFixture("allow.json"));
            });

        var client = host.GetTestClient();
        var request = new HttpRequestMessage(HttpMethod.Get, "/api/hello");
        request.Headers.Add("X-Test-User", "alice");
        var response = await client.SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.NotNull(capturedRequest);
        Assert.Equal("alice", capturedRequest!.IdentityKey);
        Assert.Equal("PRINCIPAL", capturedRequest.IdentityType);
    }

    [Fact]
    public async Task SafeHeadersExcludeForwardingAndCredentialHeaders()
    {
        EvaluationRequest? capturedRequest = null;
        await using var host = await CreateHostAsync(
            enabled: true,
            request =>
            {
                capturedRequest = request;
                return Task.FromResult(FixturePaths.ReadResponseFixture("allow.json"));
            });

        var client = host.GetTestClient();
        var request = new HttpRequestMessage(HttpMethod.Get, "/api/hello");
        request.Headers.Add("X-Forwarded-For", "203.0.113.10");
        request.Headers.Add("Forwarded", "for=203.0.113.10");
        request.Headers.Add("X-Real-IP", "203.0.113.11");
        request.Headers.Add("X-Forwarded-Host", "evil.example");
        request.Headers.Add("X-Forwarded-Proto", "http");
        request.Headers.Add("X-AI-Sentinel-Api-Key", "inbound-secret");
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer secret");
        request.Headers.TryAddWithoutValidation("Cookie", "session=secret");
        request.Headers.Add("X-Custom-Secret", "custom-secret");
        request.Headers.Add("X-Csrf-Token", "csrf-secret");
        request.Headers.Add("X-Session-Token", "session-secret");
        request.Headers.Add("X-Api-Key", "api-secret");
        request.Headers.Add("X-Request-ID", "safe-request-id");
        request.Headers.UserAgent.ParseAdd("safe-agent");

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.NotNull(capturedRequest);
        Assert.Equal("safe-request-id", capturedRequest!.Headers["x-request-id"]);
        Assert.Equal("safe-agent", capturedRequest.Headers["user-agent"]);
        Assert.DoesNotContain("x-forwarded-for", capturedRequest.Headers.Keys);
        Assert.DoesNotContain("forwarded", capturedRequest.Headers.Keys);
        Assert.DoesNotContain("x-real-ip", capturedRequest.Headers.Keys);
        Assert.DoesNotContain("x-forwarded-host", capturedRequest.Headers.Keys);
        Assert.DoesNotContain("x-forwarded-proto", capturedRequest.Headers.Keys);
        Assert.DoesNotContain("x-ai-sentinel-api-key", capturedRequest.Headers.Keys);
        Assert.DoesNotContain("authorization", capturedRequest.Headers.Keys);
        Assert.DoesNotContain("cookie", capturedRequest.Headers.Keys);
        Assert.DoesNotContain("x-custom-secret", capturedRequest.Headers.Keys);
        Assert.DoesNotContain("x-csrf-token", capturedRequest.Headers.Keys);
        Assert.DoesNotContain("x-session-token", capturedRequest.Headers.Keys);
        Assert.DoesNotContain("x-api-key", capturedRequest.Headers.Keys);
    }

    [Fact]
    public void RequestMapperUsesConnectionRemoteAddress()
    {
        var context = new DefaultHttpContext();
        context.Connection.RemoteIpAddress = IPAddress.Parse("198.51.100.7");
        var mapper = new DefaultEvaluationRequestMapper(
            Microsoft.Extensions.Options.Options.Create(new AiSentinelOptions()),
            new ClaimsIdentityResolver(Microsoft.Extensions.Options.Options.Create(new AiSentinelOptions())));

        var request = mapper.Map(context, "corr-remote-address");

        Assert.Equal("198.51.100.7", request.RemoteAddress);
    }

    [Fact]
    public async Task DecisionObserverCannotMutateBlockIntoProceed()
    {
        await using var host = await CreateHostAsync(
            enabled: true,
            _ => Task.FromResult(FixturePaths.ReadResponseFixture("block.json")),
            configureServices: services =>
            {
                services.AddSingleton<Observability.ISentinelDecisionObserver, MutatingDecisionObserver>();
            });

        var response = await host.GetTestClient().GetAsync("/api/hello");

        Assert.Equal((HttpStatusCode)429, response.StatusCode);
    }

    [Fact]
    public async Task RequestBodyRemainsAvailableToDownstreamEndpoint()
    {
        await using var host = await CreateHostAsync(
            enabled: true,
            _ => Task.FromResult(FixturePaths.ReadResponseFixture("allow.json")),
            app =>
            {
                app.UseRouting();
                app.UseEndpoints(endpoints =>
                    endpoints.MapPost("/api/echo", async context =>
                    {
                        using var reader = new StreamReader(context.Request.Body);
                        var body = await reader.ReadToEndAsync();
                        await context.Response.WriteAsync(body);
                    }));
            });

        var response = await host.GetTestClient().PostAsync(
            "/api/echo",
            new StringContent("{\"hello\":\"world\"}"));
        var body = await response.Content.ReadAsStringAsync();

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("{\"hello\":\"world\"}", body);
    }

    private static async Task<TestHostHandle> CreateHostAsync(
        bool enabled,
        Func<EvaluationRequest, Task<EvaluationResponse>> evaluate,
        Action<IApplicationBuilder>? configurePipeline = null,
        Action<IServiceCollection>? configureServices = null)
    {
        var fake = new FakeRemoteClient(evaluate);
        var builder = Host.CreateDefaultBuilder()
            .ConfigureWebHost(web =>
            {
                web.UseTestServer();
                web.ConfigureServices(services =>
                {
                    services.AddRouting();
                    services.AddAuthentication();
                    services.AddAuthorization();
                    services.AddSingleton(fake);
                    services.AddSingleton<IRemoteEvaluationClient>(fake);
                    services.AddOptions<Options.AiSentinelOptions>().Configure(o =>
                    {
                        o.Enabled = enabled;
                        o.ServiceUrl = "http://127.0.0.1:9";
                        o.ApiKey = "test-key";
                        o.RequireHttps = false;
                    });
                    services.AddSingleton<Observability.ISentinelTelemetry, Observability.SentinelTelemetry>();
                    services.AddSingleton<Mapping.IIdentityResolver, Mapping.ClaimsIdentityResolver>();
                    services.AddSingleton<Mapping.IEvaluationRequestMapper, Mapping.DefaultEvaluationRequestMapper>();
                    services.AddSingleton<Observability.ISentinelDecisionObserver, ObservingDecisionObserver>();
                    services.AddSingleton<Observability.ISentinelFailureObserver, ObservingFailureObserver>();
                    configureServices?.Invoke(services);
                });
                web.Configure(app =>
                {
                    app.UseAuthentication();
                    app.Use(async (context, next) =>
                    {
                        if (context.Request.Headers.TryGetValue("X-Test-User", out var user))
                        {
                            var identity = new ClaimsIdentity("Test");
                            identity.AddClaim(new Claim(ClaimTypes.NameIdentifier, user.ToString()));
                            context.User = new ClaimsPrincipal(identity);
                        }

                        await next();
                    });
                    app.UseAiSentinel();
                    configurePipeline?.Invoke(app);
                    app.UseRouting();
                    app.UseEndpoints(endpoints => endpoints.MapGet("/api/hello", () => Results.Ok(new { ok = true })));
                });
            });

        var host = builder.Build();
        await host.StartAsync();
        return new TestHostHandle(host);
    }

    private sealed class TestHostHandle : IAsyncDisposable
    {
        private readonly IHost _host;

        public TestHostHandle(IHost host) => _host = host;

        public HttpClient GetTestClient() => _host.GetTestClient();

        public async ValueTask DisposeAsync()
        {
            await _host.StopAsync();
            _host.Dispose();
        }
    }

    private sealed class FakeRemoteClient : IRemoteEvaluationClient
    {
        private readonly Func<EvaluationRequest, Task<EvaluationResponse>> _evaluate;

        public FakeRemoteClient(Func<EvaluationRequest, Task<EvaluationResponse>> evaluate)
        {
            _evaluate = evaluate;
        }

        public Task<EvaluationResponse> EvaluateAsync(
            EvaluationRequest request,
            CancellationToken cancellationToken = default) =>
            _evaluate(request);
    }

    private sealed class ObservingDecisionObserver : Observability.ISentinelDecisionObserver
    {
        public void OnDecision(HttpContext context, EvaluationRequest request, EvaluationResponse response)
        {
        }
    }

    private sealed class MutatingDecisionObserver : Observability.ISentinelDecisionObserver
    {
        public void OnDecision(HttpContext context, EvaluationRequest request, EvaluationResponse response)
        {
            response.Action = EnforcementAction.ALLOW;
            response.Proceed = true;
        }
    }

    private sealed class ObservingFailureObserver : Observability.ISentinelFailureObserver
    {
        public void OnRemoteFailure(HttpContext context, EvaluationRequest request, EvaluationResponse response)
        {
        }
    }
}
