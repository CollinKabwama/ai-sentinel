using System.Text.Json;
using System.Text.Json.Serialization;
using AI.Sentinel.AspNetCore.Contract;

namespace AI.Sentinel.AspNetCore.Tests.Support;

internal static class FixturePaths
{
    public static string RepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            if (File.Exists(Path.Combine(dir.FullName, "dotnet", "AI.Sentinel.sln")))
            {
                return dir.FullName;
            }

            dir = dir.Parent;
        }

        throw new InvalidOperationException("Could not locate repository root");
    }

    public static string ResponseFixture(string name) =>
        Path.Combine(RepoRoot(), "dotnet", "fixtures", "responses", name);

    public static string RequestFixture(string name) =>
        Path.Combine(RepoRoot(), "dotnet", "fixtures", "requests", name);

    public static EvaluationResponse ReadResponseFixture(string fileName)
    {
        var json = File.ReadAllText(ResponseFixture(fileName));
        return JsonSerializer.Deserialize<EvaluationResponse>(json, TestJson.Options)
            ?? throw new InvalidOperationException("fixture deserialize failed");
    }
}

internal static class TestJson
{
    public static readonly JsonSerializerOptions Options = CreateOptions();

    private static JsonSerializerOptions CreateOptions()
    {
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
        };
        options.Converters.Add(new JsonStringEnumConverter());
        return options;
    }
}
