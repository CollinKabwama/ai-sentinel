using AI.Sentinel.AspNetCore.Options;

namespace AI.Sentinel.AspNetCore.Tests;

public class AiSentinelOptionsTests
{
    [Fact]
    public void DisabledSkipsValidation()
    {
        var options = new AiSentinelOptions { Enabled = false };
        options.Validate();
    }

    [Fact]
    public void EnabledRequiresServiceUrlAndApiKey()
    {
        var options = new AiSentinelOptions { Enabled = true };
        Assert.Throws<InvalidOperationException>(() => options.Validate());

        options.ServiceUrl = "http://127.0.0.1:8080";
        Assert.Throws<InvalidOperationException>(() => options.Validate());

        options.ApiKey = "key";
        options.Validate();
    }

    [Fact]
    public void RequireHttpsRejectsNonLoopbackHttp()
    {
        var options = new AiSentinelOptions
        {
            Enabled = true,
            ServiceUrl = "http://example.com",
            ApiKey = "key",
            RequireHttps = true
        };
        Assert.Throws<InvalidOperationException>(() => options.Validate());
    }
}
