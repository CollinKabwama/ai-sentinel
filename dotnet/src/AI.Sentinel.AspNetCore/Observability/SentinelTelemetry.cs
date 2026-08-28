using System.Diagnostics.Metrics;
using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Remote;
using Microsoft.Extensions.Logging;

namespace AI.Sentinel.AspNetCore.Observability;

/// <summary>Bounded adapter telemetry with closed outcome/action labels.</summary>
public sealed class SentinelTelemetry : ISentinelTelemetry
{
    public static readonly Meter Meter = new("AI.Sentinel.AspNetCore", "0.1.0");

    private readonly Counter<long> _attempts;
    private readonly Counter<long> _successes;
    private readonly Counter<long> _failures;
    private readonly Counter<long> _outcomes;
    private readonly Histogram<double> _latencyMs;
    private readonly ILogger<SentinelTelemetry> _logger;

    public SentinelTelemetry(ILogger<SentinelTelemetry> logger)
    {
        _logger = logger;
        _attempts = Meter.CreateCounter<long>("aisentinel.aspnet.evaluation.attempt");
        _successes = Meter.CreateCounter<long>("aisentinel.aspnet.evaluation.success");
        _failures = Meter.CreateCounter<long>("aisentinel.aspnet.evaluation.failure");
        _outcomes = Meter.CreateCounter<long>("aisentinel.aspnet.remote.outcome");
        _latencyMs = Meter.CreateHistogram<double>("aisentinel.aspnet.remote.latency.ms");
    }

    public void RecordAttempt() => _attempts.Add(1);

    public void RecordSuccess(string action, IReadOnlyList<string> statuses)
    {
        _successes.Add(1, new KeyValuePair<string, object?>("action", action));
        _logger.LogDebug(
            "AI-Sentinel evaluation success action={Action} statuses={Statuses}",
            action,
            string.Join(',', statuses));
    }

    public void RecordFailure(RemoteEvaluationOutcome outcome)
    {
        _failures.Add(1, new KeyValuePair<string, object?>("outcome", outcome.ToString()));
    }

    public void RecordOutcome(RemoteEvaluationOutcome outcome)
    {
        _outcomes.Add(1, new KeyValuePair<string, object?>("outcome", outcome.ToString()));
    }

    public void RecordLatency(TimeSpan elapsed)
    {
        _latencyMs.Record(elapsed.TotalMilliseconds);
    }
}

public sealed class NoOpSentinelDecisionObserver : ISentinelDecisionObserver
{
    public void OnDecision(HttpContext context, EvaluationRequest request, EvaluationResponse response)
    {
    }
}

public sealed class NoOpSentinelFailureObserver : ISentinelFailureObserver
{
    public void OnRemoteFailure(HttpContext context, EvaluationRequest request, EvaluationResponse response)
    {
    }
}
