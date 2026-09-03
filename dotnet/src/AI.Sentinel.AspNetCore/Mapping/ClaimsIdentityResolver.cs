using AI.Sentinel.AspNetCore.Options;
using Microsoft.Extensions.Options;

namespace AI.Sentinel.AspNetCore.Mapping;

/// <summary>Derives identity from HttpContext.User claims (trusted host authentication).</summary>
public sealed class ClaimsIdentityResolver : IIdentityResolver
{
    private readonly AiSentinelOptions _options;

    public ClaimsIdentityResolver(IOptions<AiSentinelOptions> options)
    {
        _options = options.Value;
    }

    public IdentityResolution Resolve(HttpContext context)
    {
        var user = context.User;
        if (user?.Identity?.IsAuthenticated == true)
        {
            var claim = user.FindFirst(_options.IdentityClaimType)
                ?? user.FindFirst(System.Security.Claims.ClaimTypes.NameIdentifier)
                ?? user.FindFirst(System.Security.Claims.ClaimTypes.Name);
            if (claim != null && !string.IsNullOrWhiteSpace(claim.Value))
            {
                return new IdentityResolution(claim.Value, "PRINCIPAL");
            }
        }

        return new IdentityResolution(string.Empty, "ANONYMOUS");
    }
}
