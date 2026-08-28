using System.Diagnostics;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using AI.Sentinel.AspNetCore.Contract;
using AI.Sentinel.AspNetCore.Internal;
using AI.Sentinel.AspNetCore.Observability;
using AI.Sentinel.AspNetCore.Options;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace AI.Sentinel.AspNetCore.Remote;

/// <summary>
/// Authenticated HTTP client for remote evaluation. No automatic retries.
/// Transport failures yield REMOTE_EVALUATION_FAILURE fail-open responses.
/// </summary>
public sealed class RemoteEvaluationClient : IRemoteEvaluationClient
{
    private readonly HttpClient _httpClient;
    private readonly AiSentinelOptions _options;
    private readonly ISentinelTelemetry _telemetry;
    private readonly ILogger<RemoteEvaluationClient> _logger;

    public RemoteEvaluationClient(
        HttpClient httpClient,
        IOptions<AiSentinelOptions> options,
        ISentinelTelemetry telemetry,
        ILogger<RemoteEvaluationClient> logger)
    {
        _httpClient = httpClient;
        _options = options.Value;
        _telemetry = telemetry;
        _logger = logger;
    }

    public async Task<EvaluationResponse> EvaluateAsync(
        EvaluationRequest request,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        _telemetry.RecordAttempt();
        var stopwatch = Stopwatch.StartNew();
        var outcome = RemoteEvaluationOutcome.UNEXPECTED;

        try
        {
            using var httpRequest = new HttpRequestMessage(HttpMethod.Post, _options.EvaluationPath)
            {
                Content = JsonContent.Create(request, options: SentinelJson.Options)
            };
            httpRequest.Headers.TryAddWithoutValidation(
                RemoteEvaluationConstants.ApiKeyHeader,
                _options.ApiKey);

            using var httpResponse = await _httpClient.SendAsync(
                httpRequest,
                HttpCompletionOption.ResponseContentRead,
                cancellationToken).ConfigureAwait(false);

            if (httpResponse.StatusCode == System.Net.HttpStatusCode.Unauthorized
                || httpResponse.StatusCode == System.Net.HttpStatusCode.Forbidden)
            {
                outcome = RemoteEvaluationOutcome.AUTH_REJECTED;
                return Fail(request.CorrelationId, outcome);
            }

            if (!httpResponse.IsSuccessStatusCode)
            {
                outcome = RemoteEvaluationOutcome.HTTP_ERROR;
                return Fail(request.CorrelationId, outcome);
            }

            var raw = await httpResponse.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
            if (string.IsNullOrWhiteSpace(raw))
            {
                outcome = RemoteEvaluationOutcome.MALFORMED_RESPONSE;
                return Fail(request.CorrelationId, outcome);
            }

            EvaluationResponse? response;
            try
            {
                response = JsonSerializer.Deserialize<EvaluationResponse>(raw, SentinelJson.Options);
            }
            catch (JsonException)
            {
                outcome = RemoteEvaluationOutcome.MALFORMED_RESPONSE;
                return Fail(request.CorrelationId, outcome);
            }

            if (response == null)
            {
                outcome = RemoteEvaluationOutcome.MALFORMED_RESPONSE;
                return Fail(request.CorrelationId, outcome);
            }

            try
            {
                EvaluationResponseValidator.Validate(response, request.CorrelationId);
            }
            catch (EvaluationContractException ex)
            {
                var message = ex.Message ?? string.Empty;
                if (message.Contains("contractVersion", StringComparison.Ordinal))
                {
                    outcome = RemoteEvaluationOutcome.VERSION_MISMATCH;
                }
                else if (message.Contains("correlationId", StringComparison.Ordinal))
                {
                    outcome = RemoteEvaluationOutcome.CORRELATION_MISMATCH;
                }
                else
                {
                    outcome = RemoteEvaluationOutcome.MALFORMED_RESPONSE;
                }

                return Fail(request.CorrelationId, outcome);
            }

            outcome = RemoteEvaluationOutcome.SUCCESS;
            _telemetry.RecordSuccess(response.Action!.Value.ToString(), response.EvaluationStatuses);
            return response;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            outcome = RemoteEvaluationOutcome.TIMEOUT;
            return Fail(request.CorrelationId, outcome);
        }
        catch (TaskCanceledException)
        {
            outcome = RemoteEvaluationOutcome.TIMEOUT;
            return Fail(request.CorrelationId, outcome);
        }
        catch (HttpRequestException)
        {
            outcome = RemoteEvaluationOutcome.CONNECTION_FAILURE;
            return Fail(request.CorrelationId, outcome);
        }
        catch (JsonException)
        {
            outcome = RemoteEvaluationOutcome.SERIALIZATION_FAILURE;
            return Fail(request.CorrelationId, outcome);
        }
        catch (Exception)
        {
            outcome = RemoteEvaluationOutcome.UNEXPECTED;
            return Fail(request.CorrelationId, outcome);
        }
        finally
        {
            stopwatch.Stop();
            _telemetry.RecordOutcome(outcome);
            _telemetry.RecordLatency(stopwatch.Elapsed);
        }
    }

    private EvaluationResponse Fail(string correlationId, RemoteEvaluationOutcome outcome)
    {
        _logger.LogWarning(
            "Remote evaluation failed outcome={Outcome} correlationId={CorrelationId}",
            outcome,
            correlationId);
        _telemetry.RecordFailure(outcome);
        return EvaluationFailureResponses.RemoteFailure(correlationId);
    }
}
