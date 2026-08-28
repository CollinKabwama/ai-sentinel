using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Observability;
using AI.Sentinel.AspNetCore.Options;
using AI.Sentinel.AspNetCore.Remote;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace AI.Sentinel.AspNetCore.Tests;

public class LiveCrossRuntimeE2ETests
{
    [Fact]
    public async Task DotNetClientCanEvaluateAgainstLiveJavaRemoteServiceWhenConfigured()
    {
        var serviceUrl = Environment.GetEnvironmentVariable("AI_SENTINEL_E2E_SERVICE_URL");
        var apiKey = Environment.GetEnvironmentVariable("AI_SENTINEL_E2E_API_KEY");
        if (string.IsNullOrWhiteSpace(serviceUrl) || string.IsNullOrWhiteSpace(apiKey))
        {
            return;
        }

        var correlationId = "dotnet-java-e2e-" + Guid.NewGuid().ToString("N");
        var request = new EvaluationRequest
        {
            CorrelationId = correlationId,
            Method = "GET",
            Path = "/api/dotnet-e2e",
            IdentityKey = "dotnet-e2e-user",
            IdentityType = "PRINCIPAL",
            RemoteAddress = "127.0.0.1",
            Headers = new Dictionary<string, string>
            {
                ["user-agent"] = "ai-sentinel-dotnet-e2e"
            },
            Parameters = new Dictionary<string, string>
            {
                ["probe"] = "true"
            }
        };

        var response = await CreateClient(serviceUrl, apiKey).EvaluateAsync(request);

        EvaluationResponseValidator.Validate(response, correlationId);
        Assert.False(response.IsRemoteEvaluationFailure);
        Assert.Equal(correlationId, response.CorrelationId);
        Assert.True(response.Proceed || response.Action is EnforcementAction.THROTTLE
            or EnforcementAction.BLOCK
            or EnforcementAction.QUARANTINE);

        var wrongKeyResponse = await CreateClient(serviceUrl, apiKey + "-wrong").EvaluateAsync(request);
        Assert.True(wrongKeyResponse.IsRemoteEvaluationFailure);
        Assert.Equal(EnforcementAction.ALLOW, wrongKeyResponse.Action);
        Assert.True(wrongKeyResponse.Proceed);
    }

    private static RemoteEvaluationClient CreateClient(string serviceUrl, string apiKey)
    {
        var options = new AiSentinelOptions
        {
            Enabled = true,
            ServiceUrl = serviceUrl,
            ApiKey = apiKey,
            RequireHttps = false,
            ConnectTimeoutMilliseconds = 1_000,
            ReadTimeoutMilliseconds = 5_000
        };
        var httpClient = new HttpClient
        {
            BaseAddress = new Uri(options.ServiceUrl.TrimEnd('/') + "/"),
            Timeout = TimeSpan.FromMilliseconds(options.ReadTimeoutMilliseconds)
        };

        return new RemoteEvaluationClient(
            httpClient,
            Microsoft.Extensions.Options.Options.Create(options),
            new SentinelTelemetry(NullLogger<SentinelTelemetry>.Instance),
            NullLogger<RemoteEvaluationClient>.Instance);
    }
}
