using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Observability;
using AI.Sentinel.AspNetCore.Remote;

namespace AI.Sentinel.AspNetCore.Tests.Support;

/// <summary>Records remote outcomes for reliability-evidence assertions.</summary>
internal sealed class RecordingSentinelTelemetry : ISentinelTelemetry
{
    public List<RemoteEvaluationOutcome> Outcomes { get; } = new();
    public List<RemoteEvaluationOutcome> Failures { get; } = new();
    public int Attempts { get; private set; }

    public void RecordAttempt() => Attempts++;

    public void RecordSuccess(string action, IReadOnlyList<string> statuses)
    {
    }

    public void RecordFailure(RemoteEvaluationOutcome outcome) => Failures.Add(outcome);

    public void RecordOutcome(RemoteEvaluationOutcome outcome) => Outcomes.Add(outcome);

    public void RecordLatency(TimeSpan elapsed)
    {
    }
}
