using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Tests.Support;

namespace AI.Sentinel.AspNetCore.Tests;

public class CrossLanguageContractFixtureTests
{
    [Theory]
    [InlineData("allow.json")]
    [InlineData("monitor.json")]
    [InlineData("throttle.json")]
    [InlineData("block.json")]
    [InlineData("quarantine.json")]
    [InlineData("remote-failure.json")]
    [InlineData("with-factors-advice.json")]
    public void SharedFixturesDeserializeAndValidate(string fileName)
    {
        var response = FixturePaths.ReadResponseFixture(fileName);
        var correlation = response.CorrelationId;
        EvaluationResponseValidator.Validate(response, correlation);
    }

    [Fact]
    public void AllowFixtureProceeds()
    {
        var response = FixturePaths.ReadResponseFixture("allow.json");
        Assert.True(response.Proceed);
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
    }

    [Fact]
    public void BlockFixtureDoesNotProceed()
    {
        var response = FixturePaths.ReadResponseFixture("block.json");
        Assert.False(response.Proceed);
        Assert.Equal(EnforcementAction.BLOCK, response.Action);
    }
}
