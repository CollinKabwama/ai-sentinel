namespace AI.Sentinel.AspNetCore.Contract;

public sealed class EvaluationContractException : Exception
{
    public EvaluationContractException(string message) : base(message)
    {
    }
}
