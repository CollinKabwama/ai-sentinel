package dev.aisentinel.autoconfigure.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aisentinel.core.contract.EvaluationRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-language request fixtures emitted/consumed by the ASP.NET reference adapter.
 */
class CrossLanguageRequestFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @ParameterizedTest
    @ValueSource(strings = {
        "anonymous.json",
        "authenticated-principal.json"
    })
    void sharedDotNetRequestFixturesDeserialize(String fileName) throws Exception {
        Path fixture = repoRoot().resolve("dotnet/fixtures/requests").resolve(fileName);
        assertThat(fixture).exists();
        String json = Files.readString(fixture);
        EvaluationRequest request = MAPPER.readValue(json, EvaluationRequest.class);
        assertThat(request.contractVersion()).isEqualTo(1);
        assertThat(request.correlationId()).isNotBlank();
        assertThat(request.path()).startsWith("/");
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
