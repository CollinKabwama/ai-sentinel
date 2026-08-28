using AI.Sentinel.AspNetCore.Contract;

namespace AI.Sentinel.AspNetCore.Tests;

public class EvaluationResponseValidatorTests
{
    [Fact]
    public void RemoteFailureShapeIsValid()
    {
        var response = EvaluationFailureResponses.RemoteFailure("corr-1");
        EvaluationResponseValidator.Validate(response, "corr-1");
        Assert.True(response.Proceed);
        Assert.Equal(EnforcementAction.ALLOW, response.Action);
    }

    [Fact]
    public void RemoteFailureWithBlockActionRejected()
    {
        var response = EvaluationFailureResponses.RemoteFailure("c");
        response.Action = EnforcementAction.BLOCK;
        response.Proceed = false;
        var ex = Assert.Throws<EvaluationContractException>(() =>
            EvaluationResponseValidator.Validate(response, "c"));
        Assert.Contains("REMOTE_EVALUATION_FAILURE", ex.Message);
    }

    [Fact]
    public void ProceedInconsistentWithActionRejected()
    {
        var response = new EvaluationResponse
        {
            ContractVersion = 1,
            CorrelationId = "c",
            Action = EnforcementAction.BLOCK,
            Proceed = true,
            EvaluationStatuses = ["COMPLETE"]
        };
        Assert.Throws<EvaluationContractException>(() =>
            EvaluationResponseValidator.Validate(response, "c"));
    }

    [Fact]
    public void VersionMismatchRejected()
    {
        var response = new EvaluationResponse
        {
            ContractVersion = 99,
            CorrelationId = "c",
            Action = EnforcementAction.ALLOW,
            Proceed = true
        };
        Assert.Throws<EvaluationContractException>(() =>
            EvaluationResponseValidator.Validate(response, "c"));
    }
}
