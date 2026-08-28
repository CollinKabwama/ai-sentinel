using System.Security.Claims;
using AI.Sentinel.AspNetCore.Middleware;

var builder = WebApplication.CreateBuilder(args);

// Development-only identity for the reference sample. Host applications must use real authentication.
if (builder.Environment.IsDevelopment())
{
    builder.Services.AddAuthentication("Dev")
        .AddScheme<Microsoft.AspNetCore.Authentication.AuthenticationSchemeOptions, DevAuthenticationHandler>(
            "Dev",
            _ => { });
}

builder.Services.AddAuthorization();
builder.Services.AddAiSentinel(builder.Configuration);

var app = builder.Build();

if (app.Environment.IsDevelopment())
{
    app.UseAuthentication();
}

app.UseAuthorization();
app.UseAiSentinel();

app.MapGet("/", () => Results.Ok(new
{
    message = "AI-Sentinel ASP.NET Core reference sample",
    monitorFirst = true
}));

app.MapGet("/api/orders", () => Results.Ok(new { orders = Array.Empty<object>() }))
    .RequireAuthorization();

app.Run();

/// <summary>Development-only handler — NOT for production.</summary>
internal sealed class DevAuthenticationHandler : Microsoft.AspNetCore.Authentication.AuthenticationHandler<Microsoft.AspNetCore.Authentication.AuthenticationSchemeOptions>
{
    public DevAuthenticationHandler(
        Microsoft.Extensions.Options.IOptionsMonitor<Microsoft.AspNetCore.Authentication.AuthenticationSchemeOptions> options,
        Microsoft.Extensions.Logging.ILoggerFactory logger,
        System.Text.Encodings.Web.UrlEncoder encoder)
        : base(options, logger, encoder)
    {
    }

    protected override Task<Microsoft.AspNetCore.Authentication.AuthenticateResult> HandleAuthenticateAsync()
    {
        var identity = new ClaimsIdentity("Dev");
        identity.AddClaim(new Claim(ClaimTypes.NameIdentifier, "dev-user-1"));
        identity.AddClaim(new Claim(ClaimTypes.Name, "dev-user"));
        var principal = new ClaimsPrincipal(identity);
        var ticket = new Microsoft.AspNetCore.Authentication.AuthenticationTicket(principal, "Dev");
        return Task.FromResult(Microsoft.AspNetCore.Authentication.AuthenticateResult.Success(ticket));
    }
}
