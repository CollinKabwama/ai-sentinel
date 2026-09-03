using System.ComponentModel.DataAnnotations;

namespace AI.Sentinel.AspNetCore.Options;

/// <summary>ASP.NET adapter configuration. Server-owned policy/scoring settings are not exposed here.</summary>
public sealed class AiSentinelOptions
{
    public const string SectionName = "AiSentinel";

    /// <summary>When false, middleware is a no-op.</summary>
    public bool Enabled { get; set; }

    /// <summary>Remote evaluation service base URL (e.g. https://sentinel.example.com).</summary>
    [Required]
    public string ServiceUrl { get; set; } = string.Empty;

    /// <summary>Shared API key sent as X-AI-Sentinel-Api-Key.</summary>
    [Required]
    public string ApiKey { get; set; } = string.Empty;

    /// <summary>Evaluation endpoint path.</summary>
    public string EvaluationPath { get; set; } = Contract.EvaluationContractConstants.DefaultEvaluationPath;

    /// <summary>HTTP connect timeout in milliseconds.</summary>
    [Range(50, 30_000)]
    public int ConnectTimeoutMilliseconds { get; set; } = 500;

    /// <summary>HTTP read timeout in milliseconds.</summary>
    [Range(50, 60_000)]
    public int ReadTimeoutMilliseconds { get; set; } = 2000;

    /// <summary>When true, reject non-HTTPS service URLs except loopback HTTP for local tests.</summary>
    public bool RequireHttps { get; set; } = true;

    /// <summary>Claim type used to derive identityKey from HttpContext.User.</summary>
    public string IdentityClaimType { get; set; } = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier";

    /// <summary>HTTP status when proceed=false (default 429, aligned with Java block status).</summary>
    [Range(400, 599)]
    public int DenyStatusCode { get; set; } = 429;

    /// <summary>Minimal client-facing body when enforcement prevents continuation.</summary>
    public string DenyResponseBody { get; set; } = "{\"error\":\"request_denied\"}";

    /// <summary>Include selected safe request headers in EvaluationRequest (never Authorization/Cookie).</summary>
    public bool IncludeSafeHeaders { get; set; } = true;

    public void Validate()
    {
        if (!Enabled)
        {
            return;
        }

        if (string.IsNullOrWhiteSpace(ServiceUrl))
        {
            throw new InvalidOperationException("AiSentinel:ServiceUrl is required when Enabled=true");
        }

        if (string.IsNullOrWhiteSpace(ApiKey))
        {
            throw new InvalidOperationException("AiSentinel:ApiKey is required when Enabled=true");
        }

        if (!Uri.TryCreate(ServiceUrl, UriKind.Absolute, out var uri))
        {
            throw new InvalidOperationException("AiSentinel:ServiceUrl must be an absolute URI");
        }

        if (RequireHttps && uri.Scheme.Equals("http", StringComparison.OrdinalIgnoreCase)
            && !IsLoopback(uri))
        {
            throw new InvalidOperationException(
                "AiSentinel:ServiceUrl must use https when RequireHttps=true (loopback http allowed for tests)");
        }
    }

    private static bool IsLoopback(Uri uri)
    {
        return uri.IsLoopback
            || uri.Host.Equals("localhost", StringComparison.OrdinalIgnoreCase)
            || uri.Host == "127.0.0.1"
            || uri.Host == "[::1]";
    }
}
