using AI.Sentinel.AspNetCore.Options;
using AI.Sentinel.AspNetCore.Remote;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace AI.Sentinel.AspNetCore.Tests.Support;

internal static class SentinelTestServices
{
    public static (IServiceProvider Services, StubHttpMessageHandler Handler) CreateClientServices(
        StubHttpMessageHandler handler,
        Action<AiSentinelOptions>? configure = null)
    {
        var options = new AiSentinelOptions
        {
            Enabled = true,
            ServiceUrl = "http://127.0.0.1:9",
            ApiKey = "test-api-key-secret",
            RequireHttps = false,
            ReadTimeoutMilliseconds = 5_000,
            ConnectTimeoutMilliseconds = 500
        };
        configure?.Invoke(options);

        var services = new ServiceCollection();
        services.AddLogging();
        services.AddSingleton(Microsoft.Extensions.Options.Options.Create(options));
        services.AddSingleton<Observability.ISentinelTelemetry, Observability.SentinelTelemetry>();
        services.AddSingleton<HttpMessageHandler>(handler);
        services.AddHttpClient<IRemoteEvaluationClient, RemoteEvaluationClient>()
            .ConfigurePrimaryHttpMessageHandler(sp => sp.GetRequiredService<HttpMessageHandler>())
            .ConfigureHttpClient(client =>
            {
                client.BaseAddress = new Uri(options.ServiceUrl.TrimEnd('/') + "/");
                client.Timeout = TimeSpan.FromMilliseconds(options.ReadTimeoutMilliseconds);
            });

        return (services.BuildServiceProvider(), handler);
    }
}
