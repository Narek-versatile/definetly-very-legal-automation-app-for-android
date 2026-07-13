using System.Text.Json;
using System.Text.RegularExpressions;

namespace AutoFlow.Core;

/// <summary>Per-step retry policy. Mirrors the Python RetryPolicy / Android retry.</summary>
public sealed record RetryPolicy(int Attempts = 1, int BackoffMs = 600)
{
    public static RetryPolicy FromJson(JsonElement? obj)
    {
        if (obj is not { ValueKind: JsonValueKind.Object } o)
            return new RetryPolicy();
        int attempts = o.TryGetProperty("attempts", out var a) && a.TryGetInt32(out var an) ? Math.Max(1, an) : 1;
        int backoff = o.TryGetProperty("backoffMs", out var b) && b.TryGetInt32(out var bn) ? bn : 600;
        return new RetryPolicy(attempts, backoff);
    }
}

/// <summary>
/// A single step: a type discriminator, a retry policy, and the remaining JSON
/// properties kept as raw params. Kept deliberately light (like the Python model)
/// so the engine dispatches by type and each driver reads what it needs.
/// </summary>
public sealed class Step
{
    public string Type { get; }
    public RetryPolicy Retry { get; }
    public IReadOnlyDictionary<string, JsonElement> Raw { get; }

    public Step(string type, RetryPolicy retry, IReadOnlyDictionary<string, JsonElement> raw)
    {
        Type = type;
        Retry = retry;
        Raw = raw;
    }
}

/// <summary>A named, ordered list of steps with default {token} values.</summary>
public sealed class Automation
{
    public string Name { get; init; } = "";
    public string Description { get; init; } = "";
    public Dictionary<string, string> Vars { get; init; } = new();
    public List<Step> Steps { get; init; } = new();

    public static Automation Load(string path) => Parse(File.ReadAllText(path));

    public static Automation Parse(string json)
    {
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;

        var vars = new Dictionary<string, string>();
        if (root.TryGetProperty("vars", out var v) && v.ValueKind == JsonValueKind.Object)
            foreach (var prop in v.EnumerateObject())
                vars[prop.Name] = prop.Value.GetString() ?? "";

        var steps = new List<Step>();
        if (root.TryGetProperty("steps", out var stepsEl) && stepsEl.ValueKind == JsonValueKind.Array)
        {
            foreach (var raw in stepsEl.EnumerateArray())
            {
                var type = raw.GetProperty("type").GetString()
                           ?? throw new InvalidOperationException("step missing 'type'");
                RetryPolicy retry = raw.TryGetProperty("retry", out var r)
                    ? RetryPolicy.FromJson(r.Clone())
                    : new RetryPolicy();

                var pars = new Dictionary<string, JsonElement>();
                foreach (var prop in raw.EnumerateObject())
                {
                    if (prop.Name is "type" or "retry") continue;
                    // Clone: JsonElements are invalidated when the JsonDocument is disposed.
                    pars[prop.Name] = prop.Value.Clone();
                }
                steps.Add(new Step(type, retry, pars));
            }
        }

        return new Automation
        {
            Name = root.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "",
            Description = root.TryGetProperty("description", out var d) ? d.GetString() ?? "" : "",
            Vars = vars,
            Steps = steps,
        };
    }
}

/// <summary>{token} substitution — mirrors the Python resolve() and Android resolve().</summary>
public static class Tokens
{
    private static readonly Regex TokenRe = new(@"\{([a-zA-Z0-9_]+)\}", RegexOptions.Compiled);

    public static string? Resolve(string? text, IReadOnlyDictionary<string, string> variables)
    {
        if (text is null) return null;
        return TokenRe.Replace(text, m =>
            variables.TryGetValue(m.Groups[1].Value, out var val) ? val : m.Value);
    }
}

/// <summary>
/// Resolved view over a step's params: typed accessors that substitute {tokens}
/// in string values. This is what each driver method reads from.
/// </summary>
public sealed class Params
{
    private readonly IReadOnlyDictionary<string, JsonElement> _raw;
    private readonly IReadOnlyDictionary<string, string> _vars;

    public Params(IReadOnlyDictionary<string, JsonElement> raw, IReadOnlyDictionary<string, string> vars)
    {
        _raw = raw;
        _vars = vars;
    }

    public bool Has(string key) => _raw.ContainsKey(key);

    public string? Str(string key, string? fallback = null)
    {
        if (_raw.TryGetValue(key, out var el) && el.ValueKind == JsonValueKind.String)
            return Tokens.Resolve(el.GetString(), _vars);
        return fallback;
    }

    public int Int(string key, int fallback)
    {
        if (_raw.TryGetValue(key, out var el) && el.ValueKind == JsonValueKind.Number
            && el.TryGetInt32(out var n))
            return n;
        return fallback;
    }

    public bool Bool(string key, bool fallback)
    {
        if (_raw.TryGetValue(key, out var el))
            return el.ValueKind switch
            {
                JsonValueKind.True => true,
                JsonValueKind.False => false,
                _ => fallback,
            };
        return fallback;
    }

    public string[] StrArray(string key)
    {
        if (_raw.TryGetValue(key, out var el) && el.ValueKind == JsonValueKind.Array)
            return el.EnumerateArray()
                     .Select(e => Tokens.Resolve(e.GetString(), _vars) ?? "")
                     .ToArray();
        return Array.Empty<string>();
    }
}
