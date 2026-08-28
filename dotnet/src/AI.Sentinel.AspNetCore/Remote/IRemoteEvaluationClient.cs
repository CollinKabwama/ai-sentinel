using AI.Sentinel.AspNetCore.Contract;

namespace AI.Sentinel.AspNetCore.Remote;

public interface IRemoteEvaluationClient
{
    /// <summary>Single remote evaluation attempt (no automatic retry).</summary>
    Task<EvaluationResponse> EvaluateAsync(
        EvaluationRequest request,
        CancellationToken cancellationToken = default);
}
