using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Mapping;
using AI.Sentinel.AspNetCore.Observability;
using AI.Sentinel.AspNetCore.Options;
using AI.Sentinel.AspNetCore.Remote;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace AI.Sentinel.AspNetCore.Middleware;

/// <summary>
/// Thin ASP.NET middleware that evaluates requests via the remote AI-Sentinel service.
/// Does not contain or reimplement the behavioral-risk engine.
/// </summary>
public sealed class SentinelMiddleware
{
    public const string ResponseItemKey = "AiSentinel.EvaluationResponse";

    private readonly RequestDelegate _next;
    private readonly AiSentinelOptions _options;
    private readonly IRemoteEvaluationClient _client;
    private readonly IEvaluationRequestMapper _mapper;
    private readonly IEnumerable<ISentinelDecisionObserver> _decisionObservers;
    private readonly IEnumerable<ISentinelFailureObserver> _failureObservers;
    private readonly ILogger<SentinelMiddleware> _logger;

    public SentinelMiddleware(
        RequestDelegate next,
        IOptions<AiSentinelOptions> options,
        IRemoteEvaluationClient client,
        IEvaluationRequestMapper mapper,
        IEnumerable<ISentinelDecisionObserver> decisionObservers,
        IEnumerable<ISentinelFailureObserver> failureObservers,
        ILogger<SentinelMiddleware> logger)
    {
        _next = next;
        _options = options.Value;
        _client = client;
        _mapper = mapper;
        _decisionObservers = decisionObservers;
        _failureObservers = failureObservers;
        _logger = logger;
    }

    public async Task InvokeAsync(HttpContext context)
    {
        if (!_options.Enabled)
        {
            await _next(context).ConfigureAwait(false);
            return;
        }

        var correlationId = ResolveCorrelationId(context);
        var request = _mapper.Map(context, correlationId);
        var response = await _client.EvaluateAsync(request, context.RequestAborted).ConfigureAwait(false);
        var remoteEvaluationFailure = response.IsRemoteEvaluationFailure;
        var proceed = response.Proceed;

        context.Items[ResponseItemKey] = response;
        foreach (var observer in _decisionObservers)
        {
            observer.OnDecision(context, request, response);
        }

        if (remoteEvaluationFailure)
        {
            foreach (var observer in _failureObservers)
            {
                observer.OnRemoteFailure(context, request, response);
            }

            _logger.LogWarning(
                "AI-Sentinel remote evaluation failure; fail-open proceed correlationId={CorrelationId}",
                correlationId);
            await _next(context).ConfigureAwait(false);
            return;
        }

        if (proceed)
        {
            await _next(context).ConfigureAwait(false);
            return;
        }

        context.Response.StatusCode = _options.DenyStatusCode;
        context.Response.ContentType = "application/json";
        await context.Response.WriteAsync(_options.DenyResponseBody, context.RequestAborted)
            .ConfigureAwait(false);
    }

    private static string ResolveCorrelationId(HttpContext context)
    {
        var trace = context.TraceIdentifier;
        if (!string.IsNullOrWhiteSpace(trace))
        {
            return SanitizeCorrelation(trace);
        }

        return Guid.NewGuid().ToString("N");
    }

    private static string SanitizeCorrelation(string value)
    {
        if (value.Length > EvaluationContractConstants.MaxStringLength)
        {
            value = value[..EvaluationContractConstants.MaxStringLength];
        }

        return value;
    }
}
