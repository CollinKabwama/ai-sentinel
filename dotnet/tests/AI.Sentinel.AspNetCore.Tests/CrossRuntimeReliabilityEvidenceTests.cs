using System.Net;
using System.Text.Json;
using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Remote;
using AI.Sentinel.AspNetCore.Tests.Support;
using Microsoft.Extensions.DependencyInjection;

namespace AI.Sentinel.AspNetCore.Tests;

/// <summary>
/// Repository-controlled cross-runtime reliability evidence for the remote evaluation contract.
/// Transport/auth/parse failures yield REMOTE_EVALUATION_FAILURE (fail-open continue), not trusted decisions.
/// </summary>
public class CrossRuntimeReliabilityEvidenceTests
{
    [Fact]
    public void AdditiveUnknownFieldsAreIgnoredOnKnownGoodResponse()
    {
        var response = FixturePaths.ReadResponseFixture("allow-additive-unknown.json");
        EvaluationResponseValidator.Validate(response, response.CorrelationId);
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
        Assert.True(response.Proceed);
        Assert.False(response.IsRemoteEvaluationFailure);
        Assert.Equal(0.05, response.AnomalyScore);
    }

    [Fact]
    public void MinimalOlderCompatibleResponseRemainsValid()
    {
        var response = FixturePaths.ReadResponseFixture("allow-minimal.json");
        EvaluationResponseValidator.Validate(response, response.CorrelationId);
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
        Assert.True(response.Proceed);
        Assert.Null(response.AnomalyScore);
        Assert.Null(response.PolicyScore);
        Assert.Empty(response.Factors);
        Assert.Null(response.Advice);
    }

    [Fact]
    public async Task ClientAcceptsAdditiveUnknownFieldsAsSuccess()
    {
        var json = await File.ReadAllTextAsync(FixturePaths.ResponseFixture("allow-additive-unknown.json"));
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(json));
        var (services, _) = SentinelTestServices.CreateClientServices(handler, out var telemetry);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest
        {
            CorrelationId = "fixture-allow-additive",
            IdentityKey = "user-1",
            Path = "/api/hello"
        });

        Assert.False(response.IsRemoteEvaluationFailure);
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
        Assert.Equal(RemoteEvaluationOutcome.SUCCESS, Assert.Single(telemetry.Outcomes));
    }

    [Fact]
    public async Task ClientAcceptsMinimalOlderFixtureAsSuccess()
    {
        var json = await File.ReadAllTextAsync(FixturePaths.ResponseFixture("allow-minimal.json"));
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(json));
        var (services, _) = SentinelTestServices.CreateClientServices(handler, out var telemetry);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest
        {
            CorrelationId = "fixture-allow-minimal",
            IdentityKey = "user-1",
            Path = "/api/hello"
        });

        Assert.False(response.IsRemoteEvaluationFailure);
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
        Assert.Equal(RemoteEvaluationOutcome.SUCCESS, Assert.Single(telemetry.Outcomes));
    }

    [Fact]
    public async Task UnsupportedContractVersionIsRemoteFailureNotTrustedDecision()
    {
        var body = """
                   {"contractVersion":99,"correlationId":"ver-skew","action":"ALLOW","evaluationStatuses":["COMPLETE"],"proceed":true,"endpoint":"/"}
                   """;
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(body));
        var (services, _) = SentinelTestServices.CreateClientServices(handler, out var telemetry);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "ver-skew" });

        Assert.True(response.IsRemoteEvaluationFailure);
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
        Assert.True(response.Proceed);
        Assert.Null(response.AnomalyScore);
        Assert.Equal(RemoteEvaluationOutcome.VERSION_MISMATCH, Assert.Single(telemetry.Outcomes));
    }

    [Fact]
    public async Task UnknownActionIsRemoteFailureNotSilentAllowDecision()
    {
        var body = """
                   {"contractVersion":1,"correlationId":"unk-action","action":"DELETE_ACCOUNT","evaluationStatuses":["COMPLETE"],"proceed":true,"endpoint":"/"}
                   """;
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(body));
        var (services, _) = SentinelTestServices.CreateClientServices(handler, out var telemetry);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "unk-action" });

        Assert.True(response.IsRemoteEvaluationFailure);
        Assert.Contains("REMOTE_EVALUATION_FAILURE", response.EvaluationStatuses);
        Assert.Equal(RemoteEvaluationOutcome.MALFORMED_RESPONSE, Assert.Single(telemetry.Outcomes));
    }

    [Theory]
    [InlineData(HttpStatusCode.Unauthorized)]
    [InlineData(HttpStatusCode.Forbidden)]
    public async Task AuthFailureIgnoresSuccessShapedBody(HttpStatusCode status)
    {
        // Body claims BLOCK — must not be trusted when HTTP auth rejects.
        var body = """
                   {"contractVersion":1,"correlationId":"auth-body","action":"BLOCK","evaluationStatuses":["COMPLETE"],"proceed":false,"endpoint":"/","anomalyScore":0.99,"policyScore":0.99}
                   """;
        var handler = new StubHttpMessageHandler(_ => HttpResponses.Json(status, body));
        var (services, _) = SentinelTestServices.CreateClientServices(handler, out var telemetry);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "auth-body" });

        Assert.True(response.IsRemoteEvaluationFailure);
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
        Assert.True(response.Proceed);
        Assert.Null(response.AnomalyScore);
        Assert.Null(response.PolicyScore);
        Assert.Equal(RemoteEvaluationOutcome.AUTH_REJECTED, Assert.Single(telemetry.Outcomes));
    }

    [Fact]
    public async Task MalformedErrorBodyOnHttp500StillRemoteFailure()
    {
        var handler = new StubHttpMessageHandler(_ =>
            HttpResponses.Json(HttpStatusCode.InternalServerError, "{not-json"));
        var (services, _) = SentinelTestServices.CreateClientServices(handler, out var telemetry);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "err-body" });

        Assert.True(response.IsRemoteEvaluationFailure);
        Assert.Equal(RemoteEvaluationOutcome.HTTP_ERROR, Assert.Single(telemetry.Outcomes));
    }

    [Theory]
    [InlineData("{\"contractVersion\":1,\"correlationId\":\"bad-score\",\"action\":\"ALLOW\",\"evaluationStatuses\":[\"COMPLETE\"],\"anomalyScore\":\"NaN\",\"proceed\":true,\"endpoint\":\"/\"}")]
    [InlineData("{\"contractVersion\":1,\"correlationId\":\"bad-score\",\"action\":\"ALLOW\",\"evaluationStatuses\":[\"COMPLETE\"],\"anomalyScore\":\"not-a-number\",\"proceed\":true,\"endpoint\":\"/\"}")]
    [InlineData("{\"contractVersion\":1,\"correlationId\":\"bad-score\",\"action\":\"ALLOW\",\"evaluationStatuses\":[\"COMPLETE\"],\"policyScore\":true,\"proceed\":true,\"endpoint\":\"/\"}")]
    public async Task InvalidNumericScoreFieldsAreRemoteFailure(string body)
    {
        var handler = new StubHttpMessageHandler(_ => HttpResponses.OkJson(body));
        var (services, _) = SentinelTestServices.CreateClientServices(handler, out var telemetry);
        var client = services.GetRequiredService<IRemoteEvaluationClient>();

        var response = await client.EvaluateAsync(new EvaluationRequest { CorrelationId = "bad-score" });

        Assert.True(response.IsRemoteEvaluationFailure);
        Assert.Equal(RemoteEvaluationOutcome.MALFORMED_RESPONSE, Assert.Single(telemetry.Outcomes));
    }

    [Fact]
    public void ValidatorRejectsNonFiniteScoresWhenConstructed()
    {
        var response = new EvaluationResponse
        {
            ContractVersion = 1,
            CorrelationId = "nan",
            Action = EnforcementAction.ALLOW,
            Proceed = true,
            AnomalyScore = double.NaN,
            EvaluationStatuses = ["COMPLETE"]
        };
        var ex = Assert.Throws<EvaluationContractException>(() =>
            EvaluationResponseValidator.Validate(response, "nan"));
        Assert.Contains("anomalyScore", ex.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void RequestRoundTripPreservesCoreWireFields()
    {
        var request = new EvaluationRequest
        {
            ContractVersion = EvaluationContractConstants.ContractVersion,
            CorrelationId = "req-1",
            TimestampEpochMillis = 1_700_000_000_000L,
            Method = "POST",
            Path = "/api/orders",
            IdentityKey = "user-42",
            IdentityType = "USER",
            Headers = new Dictionary<string, string> { ["accept"] = "application/json" },
            Parameters = new Dictionary<string, string> { ["q"] = "1" }
        };

        var json = JsonSerializer.Serialize(request, TestJson.Options);
        var roundTrip = JsonSerializer.Deserialize<EvaluationRequest>(json, TestJson.Options)!;

        Assert.Equal(request.ContractVersion, roundTrip.ContractVersion);
        Assert.Equal(request.CorrelationId, roundTrip.CorrelationId);
        Assert.Equal(request.Path, roundTrip.Path);
        Assert.Equal(request.IdentityKey, roundTrip.IdentityKey);
        Assert.Equal(request.Method, roundTrip.Method);
        Assert.DoesNotContain("test-api-key-secret", json);
    }

    [Fact]
    public void ApiKeyNeverAppearsInSerializedFailureResponse()
    {
        var failure = EvaluationFailureResponses.RemoteFailure("corr-safe");
        var json = JsonSerializer.Serialize(failure, TestJson.Options);
        Assert.DoesNotContain("test-api-key-secret", json);
        Assert.Contains("REMOTE_EVALUATION_FAILURE", json);
    }
}
