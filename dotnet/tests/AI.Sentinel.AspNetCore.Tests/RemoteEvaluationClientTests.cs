using System.Net;
using System.Text.Json;
using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Remote;
using AI.Sentinel.AspNetCore.Tests.Support;
using Microsoft.Extensions.DependencyInjection;
using Xunit.Abstractions;

namespace AI.Sentinel.AspNetCore.Tests;

public class RemoteEvaluationClientTests
{
    private readonly ITestOutputHelper _output;

    public RemoteEvaluationClientTests(ITestOutputHelper output) => _output = output;

    [Fact]
    public async Task SuccessfulAllowReturnsValidatedResponse()
    {
        var json = await File.ReadAllTextAsync(FixturePaths.ResponseFixture("allow.json"));
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(json));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest
        {
            CorrelationId = "fixture-allow",
            IdentityKey = "user-1",
            Path = "/api/hello"
        });

        Assert.Equal(EnforcementAction.ALLOW, response.Action);
        Assert.True(response.Proceed);
        Assert.Equal("fixture-allow", response.CorrelationId);
    }

    [Theory]
    [InlineData("monitor.json", "fixture-monitor")]
    [InlineData("block.json", "fixture-block")]
    [InlineData("quarantine.json", "fixture-quarantine")]
    [InlineData("throttle.json", "fixture-throttle")]
    public async Task FixtureResponsesDeserialize(string file, string correlationId)
    {
        var json = await File.ReadAllTextAsync(FixturePaths.ResponseFixture(file));
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(json));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest
        {
            CorrelationId = correlationId,
            IdentityKey = "user-1",
            Path = "/api/hello"
        });

        Assert.Equal(correlationId, response.CorrelationId);
    }

    [Fact]
    public async Task MalformedJsonFailsOpen()
    {
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson("{not-json"));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "c1" });

        Assert.True(response.IsRemoteEvaluationFailure);
        Assert.True(response.Proceed);
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
    }

    [Fact]
    public async Task EmptyBodyFailsOpen()
    {
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(""));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "c2" });
        Assert.True(response.IsRemoteEvaluationFailure);
    }

    [Fact]
    public async Task VersionMismatchFailsOpen()
    {
        var body = """
                   {"contractVersion":99,"correlationId":"c3","action":"ALLOW","evaluationStatuses":["COMPLETE"],"proceed":true,"endpoint":"/"}
                   """;
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(body));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "c3" });
        Assert.True(response.IsRemoteEvaluationFailure);
    }

    [Fact]
    public async Task InconsistentProceedFailsOpen()
    {
        var body = """
                   {"contractVersion":1,"correlationId":"c4","action":"BLOCK","evaluationStatuses":["COMPLETE"],"proceed":true,"endpoint":"/"}
                   """;
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(body));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "c4" });
        Assert.True(response.IsRemoteEvaluationFailure);
    }

    [Theory]
    [InlineData(HttpStatusCode.Unauthorized)]
    [InlineData(HttpStatusCode.Forbidden)]
    [InlineData(HttpStatusCode.BadRequest)]
    [InlineData(HttpStatusCode.InternalServerError)]
    public async Task NonSuccessHttpFailsOpen(HttpStatusCode status)
    {
        var handler = new StubHttpMessageHandler(_ => HttpResponses.Json(status, "{}"));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "c5" });
        Assert.True(response.IsRemoteEvaluationFailure);
        Assert.Null(response.AnomalyScore);
        Assert.Null(response.PolicyScore);
    }

    [Fact]
    public async Task ConnectionFailureFailsOpen()
    {
        var handler = new StubHttpMessageHandler((_, _) =>
            throw new HttpRequestException("connection refused"));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "c6" });
        Assert.True(response.IsRemoteEvaluationFailure);
    }

    [Fact]
    public async Task CancellationFailsOpen()
    {
        using var cts = new CancellationTokenSource();
        var handler = new StubHttpMessageHandler(async (_, token) =>
        {
            await Task.Delay(Timeout.Infinite, token);
            return HttpResponses.OkJson("{}");
        });
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();
        cts.Cancel();

        var response = await client.EvaluateAsync(
            new EvaluationRequest { CorrelationId = "c7" },
            cts.Token);
        Assert.True(response.IsRemoteEvaluationFailure);
    }

    [Fact]
    public async Task ApiKeySentInHeaderNotInUrl()
    {
        var json = await File.ReadAllTextAsync(FixturePaths.ResponseFixture("allow.json"));
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(json));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "fixture-allow" });

        Assert.NotNull(handler.LastRequest);
        Assert.True(handler.LastRequest!.Headers.Contains("X-AI-Sentinel-Api-Key"));
        Assert.Equal("test-api-key-secret", handler.LastRequest.Headers.GetValues("X-AI-Sentinel-Api-Key").Single());
        Assert.DoesNotContain("test-api-key-secret", handler.LastRequest.RequestUri?.ToString());
    }

    [Fact]
    public async Task SingleHttpAttemptNoRetry()
    {
        var attempts = 0;
        var handler = new StubHttpMessageHandler(_ =>
        {
            attempts++;
            return HttpResponses.Json(HttpStatusCode.InternalServerError, "{}");
        });
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "c8" });
        Assert.Equal(1, attempts);
    }

    [Fact]
    public async Task ApiKeyNotPresentInExceptionOrLoggedOutcome()
    {
        var handler = new StubHttpMessageHandler(_ => HttpResponses.Json(HttpStatusCode.InternalServerError, "{}"));
        var (services, _) = SentinelTestServices.CreateClientServices(handler);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "c9" });
        var serialized = JsonSerializer.Serialize(response);
        Assert.DoesNotContain("test-api-key-secret", serialized);
        _output.WriteLine(serialized);
    }
}
