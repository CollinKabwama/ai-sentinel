using AI.Sentinel.AspNetCore.Mapping;
using Microsoft.Extensions.Configuration;
using AI.Sentinel.AspNetCore.Middleware;
using AI.Sentinel.AspNetCore.Observability;
using AI.Sentinel.AspNetCore.Options;
using AI.Sentinel.AspNetCore.Remote;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace Microsoft.Extensions.DependencyInjection;

public static class AiSentinelServiceCollectionExtensions
{
    /// <summary>Registers the AI-Sentinel ASP.NET Core remote evaluation adapter.</summary>
    public static IServiceCollection AddAiSentinel(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        services.Configure<AiSentinelOptions>(configuration.GetSection(AiSentinelOptions.SectionName));
        services.AddOptions<AiSentinelOptions>()
            .Bind(configuration.GetSection(AiSentinelOptions.SectionName))
            .Validate(options =>
            {
                options.Validate();
                return true;
            }, "AiSentinel options validation failed");

        services.TryAddSingleton<ISentinelTelemetry, SentinelTelemetry>();
        services.TryAddEnumerable(ServiceDescriptor.Singleton<ISentinelDecisionObserver, NoOpSentinelDecisionObserver>());
        services.TryAddEnumerable(ServiceDescriptor.Singleton<ISentinelFailureObserver, NoOpSentinelFailureObserver>());
        services.TryAddSingleton<IIdentityResolver, ClaimsIdentityResolver>();
        services.TryAddSingleton<IEvaluationRequestMapper, DefaultEvaluationRequestMapper>();

        services.AddHttpClient<IRemoteEvaluationClient, RemoteEvaluationClient>((sp, client) =>
            {
                var options = sp.GetRequiredService<Microsoft.Extensions.Options.IOptions<AiSentinelOptions>>().Value;
                client.BaseAddress = new Uri(options.ServiceUrl.TrimEnd('/') + "/");
                client.Timeout = TimeSpan.FromMilliseconds(options.ReadTimeoutMilliseconds);
            })
            .ConfigurePrimaryHttpMessageHandler(sp =>
            {
                var options = sp.GetRequiredService<Microsoft.Extensions.Options.IOptions<AiSentinelOptions>>().Value;
                return new SocketsHttpHandler
                {
                    ConnectTimeout = TimeSpan.FromMilliseconds(options.ConnectTimeoutMilliseconds)
                };
            });

        return services;
    }
}
