using System.Net;
using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Remote;
using AI.Sentinel.AspNetCore.Tests.Support;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace AI.Sentinel.AspNetCore.Tests;

public class ConcurrencyTests
{
    [Fact]
    public async Task ConcurrentRemoteClientCallsRemainIsolated()
    {
        var json = await File.ReadAllTextAsync(FixturePaths.ResponseFixture("allow.json"));
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(json));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var tasks = Enumerable.Range(0, 32).Select(i => Task.Run(async () =>
        {
            var correlation = $"corr-{i}";
            var response = await client.EvaluateAsync(new EvaluationRequest
            {
                CorrelationId = correlation,
                IdentityKey = $"user-{i}",
                Path = $"/api/{i}"
            });
            return (correlation, response.CorrelationId);
        })).ToArray();

        var results = await Task.WhenAll(tasks);
        foreach (var (expected, actual) in results)
        {
            Assert.Equal(expected, actual);
        }
    }

    [Fact]
    public async Task ConcurrentMiddlewareRequestsDoNotCrossDecisions()
    {
        await using var host = await CreateHostAsync();
        var client = host.GetTestClient();
        var tasks = Enumerable.Range(0, 20).Select(async i =>
        {
            var response = await client.GetAsync($"/api/item/{i}");
            var body = await response.Content.ReadAsStringAsync();
            return (i, response.StatusCode, body);
        });
        var results = await Task.WhenAll(tasks);
        foreach (var (i, status, body) in results)
        {
            if (i % 2 == 0)
            {
                Assert.Equal(HttpStatusCode.OK, status);
                Assert.Contains("\"ok\":true", body);
            }
            else
            {
                Assert.Equal((HttpStatusCode)429, status);
            }
        }
    }

    private static async Task<TestHostHandle> CreateHostAsync()
    {
        var builder = Host.CreateDefaultBuilder()
            .ConfigureWebHost(web =>
            {
                web.UseTestServer();
                web.ConfigureServices(services =>
                {
                    services.AddRouting();
                    services.AddSingleton<IRemoteEvaluationClient, IndexAwareFakeClient>();
                    services.AddOptions<Options.AiSentinelOptions>().Configure(o =>
                    {
                        o.Enabled = true;
                        o.ServiceUrl = "http://127.0.0.1:9";
                        o.ApiKey = "test-key";
                        o.RequireHttps = false;
                    });
                    services.AddSingleton<Observability.ISentinelTelemetry, Observability.SentinelTelemetry>();
                    services.AddSingleton<Mapping.IIdentityResolver, Mapping.ClaimsIdentityResolver>();
                    services.AddSingleton<Mapping.IEvaluationRequestMapper, Mapping.DefaultEvaluationRequestMapper>();
                    services.AddSingleton<Observability.ISentinelDecisionObserver, Observability.NoOpSentinelDecisionObserver>();
                    services.AddSingleton<Observability.ISentinelFailureObserver, Observability.NoOpSentinelFailureObserver>();
                });
                web.Configure(app =>
                {
                    app.UseAiSentinel();
                    app.UseRouting();
                    app.UseEndpoints(endpoints =>
                        endpoints.MapGet("/api/item/{id:int}", (int id) => Results.Ok(new { ok = true, id })));
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

    private sealed class IndexAwareFakeClient : IRemoteEvaluationClient
    {
        public Task<EvaluationResponse> EvaluateAsync(
            EvaluationRequest request,
            CancellationToken cancellationToken = default)
        {
            var segment = request.Path.TrimEnd('/').Split('/', StringSplitOptions.RemoveEmptyEntries).LastOrDefault();
            var id = int.TryParse(segment, out var parsed) ? parsed : 0;
            var fixture = id % 2 == 0 ? "allow.json" : "block.json";
            var response = FixturePaths.ReadResponseFixture(fixture);
            response.CorrelationId = request.CorrelationId;
            return Task.FromResult(response);
        }
    }
}
