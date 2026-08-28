using AI.Sentinel.AspNetCore.Contract;

namespace AI.Sentinel.AspNetCore.Mapping;

public interface IEvaluationRequestMapper
{
    EvaluationRequest Map(HttpContext context, string correlationId);
}
