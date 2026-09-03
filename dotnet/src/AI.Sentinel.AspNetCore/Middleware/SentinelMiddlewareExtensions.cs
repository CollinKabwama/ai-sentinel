using AI.Sentinel.AspNetCore.Middleware;

namespace Microsoft.AspNetCore.Builder;

public static class SentinelMiddlewareExtensions
{
  /// <summary>Activates AI-Sentinel remote evaluation middleware.</summary>
    public static IApplicationBuilder UseAiSentinel(this IApplicationBuilder app)
    {
        return app.UseMiddleware<SentinelMiddleware>();
    }
}
