using System.Text.Json;
using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Tests.Support;

namespace AI.Sentinel.AspNetCore.Tests;

public class CrossLanguageRequestFixtureTests
{
    [Theory]
    [InlineData("anonymous.json")]
    [InlineData("authenticated-principal.json")]
    public void SharedRequestFixturesDeserialize(string fileName)
    {
        var json = File.ReadAllText(FixturePaths.RequestFixture(fileName));
        var request = JsonSerializer.Deserialize<EvaluationRequest>(json, TestJson.Options)
            ?? throw new InvalidOperationException("fixture deserialize failed");
        Assert.Equal(1, request.ContractVersion);
        Assert.False(string.IsNullOrWhiteSpace(request.CorrelationId));
        Assert.StartsWith("/", request.Path);
    }

    [Fact]
    public void AnonymousFixtureUsesContractAnonymousConvention()
    {
        var request = JsonSerializer.Deserialize<EvaluationRequest>(
            File.ReadAllText(FixturePaths.RequestFixture("anonymous.json")),
            TestJson.Options)!;
        Assert.Equal("ANONYMOUS", request.IdentityType);
        Assert.Equal(string.Empty, request.IdentityKey);
    }
}
