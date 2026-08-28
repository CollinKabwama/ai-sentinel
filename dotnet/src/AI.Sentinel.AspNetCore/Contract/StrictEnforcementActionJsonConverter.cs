using System.Text.Json;
using System.Text.Json.Serialization;

namespace AI.Sentinel.AspNetCore.Contract;

internal sealed class StrictEnforcementActionJsonConverter : JsonConverter<EnforcementAction>
{
    public override EnforcementAction Read(
        ref Utf8JsonReader reader,
        Type typeToConvert,
        JsonSerializerOptions options)
    {
        if (reader.TokenType != JsonTokenType.String)
        {
            throw new JsonException("action must be a string enum value");
        }

        var value = reader.GetString();
        if (string.IsNullOrWhiteSpace(value)
            || !Enum.TryParse<EnforcementAction>(value, ignoreCase: false, out var action)
            || !Enum.IsDefined(action))
        {
            throw new JsonException("unknown action");
        }

        return action;
    }

    public override void Write(
        Utf8JsonWriter writer,
        EnforcementAction value,
        JsonSerializerOptions options)
    {
        writer.WriteStringValue(value.ToString());
    }
}
