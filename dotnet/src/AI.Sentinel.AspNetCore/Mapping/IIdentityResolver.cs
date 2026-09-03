namespace AI.Sentinel.AspNetCore.Mapping;

/// <summary>Resolves the opaque identity key from the authenticated ASP.NET principal.</summary>
public interface IIdentityResolver
{
    IdentityResolution Resolve(HttpContext context);
}

public readonly record struct IdentityResolution(string IdentityKey, string? IdentityType);
