using System.Text.Json;
using System.Text.Json.Serialization;

namespace AI.Sentinel.AspNetCore.Internal;

internal static class SentinelJson
{
    public static readonly JsonSerializerOptions Options = CreateOptions();

    public static JsonSerializerOptions CreateOptions()
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
