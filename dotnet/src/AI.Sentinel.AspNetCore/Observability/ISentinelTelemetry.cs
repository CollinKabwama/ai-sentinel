using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Remote;

namespace AI.Sentinel.AspNetCore.Observability;

public interface ISentinelTelemetry
{
    void RecordAttempt();
    void RecordSuccess(string action, IReadOnlyList<string> statuses);
    void RecordFailure(RemoteEvaluationOutcome outcome);
    void RecordOutcome(RemoteEvaluationOutcome outcome);
    void RecordLatency(TimeSpan elapsed);
}

public interface ISentinelDecisionObserver
{
    void OnDecision(HttpContext context, EvaluationRequest request, EvaluationResponse response);
}

public interface ISentinelFailureObserver
{
    void OnRemoteFailure(HttpContext context, EvaluationRequest request, EvaluationResponse response);
}
