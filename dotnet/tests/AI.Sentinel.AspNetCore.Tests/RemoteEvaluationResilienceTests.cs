using System.Diagnostics;
using System.Net;
using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Remote;
using AI.Sentinel.AspNetCore.Tests.Support;
using Microsoft.Extensions.DependencyInjection;
using Xunit.Abstractions;

namespace AI.Sentinel.AspNetCore.Tests;

/// <summary>
/// Characterizes ASP.NET remote evaluation client resilience: no automatic retry, fail-open, and concurrency isolation.
/// </summary>
public class RemoteEvaluationResilienceTests
{
    private readonly ITestOutputHelper _output;

    public RemoteEvaluationResilienceTests(ITestOutputHelper output) => _output = output;

    [Fact]
    public async Task SuccessfulEvaluationDoesNotAutomaticallyRetry()
    {
        var hits = 0;
        var json = await File.ReadAllTextAsync(FixturePaths.ResponseFixture("allow.json"));
        var handler = new StubHttpMessageHandler(_ =>
        {
            Interlocked.Increment(ref hits);
            return HttpResponses.OkJson(json);
        });
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();
        var response = await client.EvaluateAsync(new EvaluationRequest
        {
            CorrelationId = "no-retry",
            IdentityKey = "u1",
            Path = "/api/x"
        });
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
        Assert.Equal(1, hits);
        _output.WriteLine($"no-retry hits={hits}");
    }

    [Fact]
    public async Task ConnectionFailureFailsOpenAllow()
    {
        var handler = new StubHttpMessageHandler(_ => throw new HttpRequestException("refused"));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();
        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "down" });
        Assert.True(response.IsRemoteEvaluationFailure);
        Assert.True(response.Proceed);
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
        Assert.Null(response.AnomalyScore);
    }

    [Fact]
    public async Task ConcurrentHealthyCallsRemainIsolatedAndComplete()
    {
        var json = await File.ReadAllTextAsync(FixturePaths.ResponseFixture("allow.json"));
        var hits = 0;
        var handler = new StubHttpMessageHandler(_ =>
        {
            Interlocked.Increment(ref hits);
            return HttpResponses.OkJson(json);
        });
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();
        var sw = Stopwatch.StartNew();
        var tasks = Enumerable.Range(0, 100).Select(i => Task.Run(async () =>
        {
            var r = await client.EvaluateAsync(new EvaluationRequest
            {
                CorrelationId = $"c-{i}",
                IdentityKey = $"u-{i}",
                Path = "/api/x"
            });
            return r;
        })).ToArray();
        var results = await Task.WhenAll(tasks);
        sw.Stop();
        Assert.All(results, r => Assert.True(r.Proceed));
        Assert.Equal(100, hits);
        _output.WriteLine($"concurrency=100 hits={hits} elapsedMs={sw.ElapsedMilliseconds}");
    }

    [Fact]
    public async Task Http500FailsOpen()
    {
        var handler = new StubHttpMessageHandler(_ =>
            new HttpResponseMessage(HttpStatusCode.InternalServerError));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();
        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "500" });
        Assert.True(response.IsRemoteEvaluationFailure);
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
    }
}
