package dev.aisentinel.autoconfigure.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aisentinel.core.contract.EvaluationResponse;
import dev.aisentinel.core.contract.EvaluationResponseValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-language contract fixtures shared with the ASP.NET Core reference adapter.
 */
class CrossLanguageContractFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @ValueSource(strings = {
        "allow.json",
        "monitor.json",
        "throttle.json",
        "block.json",
        "quarantine.json",
        "remote-failure.json",
        "with-factors-advice.json"
    })
    void sharedDotNetFixturesDeserializeAndValidate(String fileName) throws Exception {
        Path fixture = repoRoot().resolve("dotnet/fixtures/responses").resolve(fileName);
        assertThat(fixture).exists();
        String json = Files.readString(fixture);
        EvaluationResponse response = MAPPER.readValue(json, EvaluationResponse.class);
        EvaluationResponseValidator.validate(response, response.correlationId());
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("dotnet/AI.Sentinel.sln"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from user.dir");
    }
}
