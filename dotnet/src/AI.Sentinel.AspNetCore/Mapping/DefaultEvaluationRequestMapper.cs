using System.Diagnostics;
using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Options;
using Microsoft.Extensions.Options;

namespace AI.Sentinel.AspNetCore.Mapping;

/// <summary>Maps ASP.NET request context into the frozen EvaluationRequest contract.</summary>
public sealed class DefaultEvaluationRequestMapper : IEvaluationRequestMapper
{
    private static readonly HashSet<string> IncludedHeaders = new(StringComparer.OrdinalIgnoreCase)
    {
        "accept",
        "accept-language",
        "content-type",
        "traceparent",
        "tracestate",
        "user-agent",
        "x-correlation-id",
        "x-request-id"
    };

    private readonly AiSentinelOptions _options;
    private readonly IIdentityResolver _identityResolver;

    public DefaultEvaluationRequestMapper(
        IOptions<AiSentinelOptions> options,
        IIdentityResolver identityResolver)
    {
        _options = options.Value;
        _identityResolver = identityResolver;
    }

    public EvaluationRequest Map(HttpContext context, string correlationId)
    {
        var request = context.Request;
        var identity = _identityResolver.Resolve(context);
        var path = request.Path.HasValue ? request.Path.Value! : "/";
        if (!path.StartsWith('/'))
        {
            path = "/" + path;
        }

        var evaluationRequest = new EvaluationRequest
        {
            ContractVersion = EvaluationContractConstants.ContractVersion,
            CorrelationId = correlationId,
            TimestampEpochMillis = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Method = request.Method ?? "GET",
            Path = path,
            IdentityKey = identity.IdentityKey,
            IdentityType = identity.IdentityType,
            SessionPresent = false,
            SessionNew = false,
            RemoteAddress = context.Connection.RemoteIpAddress?.ToString()
        };

        if (_options.IncludeSafeHeaders)
        {
            foreach (var header in request.Headers)
            {
                if (evaluationRequest.Headers.Count >= EvaluationContractConstants.MaxHeaders)
                {
                    break;
                }

                var normalized = header.Key.Trim().ToLowerInvariant();
                if (normalized.Length == 0 || !IncludedHeaders.Contains(normalized))
                {
                    continue;
                }

                if (evaluationRequest.Headers.ContainsKey(normalized))
                {
                    continue;
                }

                var value = header.Value.ToString();
                if (value.Length > EvaluationContractConstants.MaxStringLength)
                {
                    value = value[..EvaluationContractConstants.MaxStringLength];
                }

                evaluationRequest.Headers[normalized] = value;
            }
        }

        foreach (var query in request.Query)
        {
            if (evaluationRequest.Parameters.Count >= EvaluationContractConstants.MaxParameters)
            {
                break;
            }

            if (string.IsNullOrWhiteSpace(query.Key))
            {
                continue;
            }

            var first = query.Value.FirstOrDefault() ?? string.Empty;
            if (first.Length > EvaluationContractConstants.MaxStringLength)
            {
                first = first[..EvaluationContractConstants.MaxStringLength];
            }

            evaluationRequest.Parameters[query.Key] = first;
        }

        var activity = Activity.Current;
        if (activity != null)
        {
            evaluationRequest.Attributes["traceId"] = activity.TraceId.ToString();
        }

        return evaluationRequest;
    }
}
