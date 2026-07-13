using AutoFlow.Core.Drivers;

namespace AutoFlow.Core;

public sealed record StepResult(int Index, string Describe, bool Ok, string Message, int Attempts);

public sealed class RunResult
{
    public string Name { get; init; } = "";
    public bool Ok { get; set; } = true;
    public List<StepResult> Steps { get; } = new();
    public string? Screenshot { get; set; }
}

/// <summary>
/// Runs an Automation's steps in order with per-step retry/backoff, structured
/// logging and a failure screenshot — the async C# mirror of the Python Engine
/// and the Android AutomationEngine.run() semantics. Steps route to the web,
/// native or generic driver by type.
/// </summary>
public sealed class Engine : IAsyncDisposable
{
    private static readonly HashSet<string> WebSteps = new()
    {
        "open_url", "wait_for", "fill", "click", "ensure_checked", "click_confirm",
        "verify", "press", "reload", "screenshot", "clear_site_data",
    };
    private static readonly HashSet<string> NativeSteps = new()
    {
        "launch", "focus_window", "ui_click", "ui_fill", "close_window",
    };
    private static readonly HashSet<string> GenericSteps = new()
    {
        "sleep", "manual", "click_xy", "type_text",
    };

    private readonly Dictionary<string, string> _variables;

    public WebDriver Web { get; }
    public NativeDriver Native { get; }
    public GenericDriver Generic { get; }

    public Engine(IReadOnlyDictionary<string, string>? variables = null, bool headed = true,
        WebDriver? web = null, NativeDriver? native = null, GenericDriver? generic = null)
    {
        _variables = variables is null ? new() : new Dictionary<string, string>(variables);
        Web = web ?? new WebDriver(headed);
        Native = native ?? new NativeDriver();
        Generic = generic ?? new GenericDriver();
    }

    public async Task<RunResult> RunAsync(Automation automation)
    {
        // CLI/session overrides win over the automation's own defaults.
        var variables = new Dictionary<string, string>(automation.Vars);
        foreach (var kv in _variables) variables[kv.Key] = kv.Value;

        var result = new RunResult { Name = automation.Name };

        for (int index = 0; index < automation.Steps.Count; index++)
        {
            var step = automation.Steps[index];
            var describe = Describe(step);
            Console.WriteLine($"  [{index + 1}/{automation.Steps.Count}] {describe}");

            int attempts = 0;
            string lastErr = "not run";
            while (attempts < step.Retry.Attempts)
            {
                attempts++;
                try
                {
                    await ExecAsync(step, variables);
                    lastErr = "";
                    break;
                }
                catch (Exception ex)
                {
                    lastErr = $"{ex.GetType().Name}: {ex.Message}";
                    if (attempts < step.Retry.Attempts)
                        await Task.Delay(step.Retry.BackoffMs);
                }
            }

            bool ok = lastErr == "";
            result.Steps.Add(new StepResult(index, describe, ok, ok ? "ok" : lastErr, attempts));
            if (!ok)
            {
                result.Ok = false;
                result.Screenshot = await CaptureFailureAsync(automation, index);
                Console.WriteLine($"      x {lastErr}");
                break;
            }
            Console.WriteLine("      ok");
        }

        return result;
    }

    // --- dispatch -----------------------------------------------------------

    private async Task ExecAsync(Step step, IReadOnlyDictionary<string, string> variables)
    {
        var p = new Params(step.Raw, variables);
        switch (step.Type)
        {
            // web
            case "open_url": await Web.OpenUrl(p); break;
            case "wait_for": await Web.WaitFor(p); break;
            case "fill": await Web.Fill(p); break;
            case "click": await Web.Click(p); break;
            case "ensure_checked": await Web.EnsureChecked(p); break;
            case "click_confirm": await Web.ClickConfirm(p); break;
            case "verify": await Web.Verify(p); break;
            case "press": await Web.Press(p); break;
            case "reload": await Web.Reload(p); break;
            case "screenshot": await Web.Screenshot(p); break;
            case "clear_site_data": await Web.ClearSiteData(p); break;
            // native
            case "launch": await Native.Launch(p); break;
            case "focus_window": await Native.FocusWindow(p); break;
            case "ui_click": await Native.UiClick(p); break;
            case "ui_fill": await Native.UiFill(p); break;
            case "close_window": await Native.CloseWindow(p); break;
            // generic
            case "sleep": await Generic.Sleep(p); break;
            case "manual": await Generic.Manual(p); break;
            case "click_xy": await Generic.ClickXy(p); break;
            case "type_text": await Generic.TypeText(p); break;
            default:
                throw new InvalidOperationException($"unknown step type: {step.Type}");
        }
    }

    private static string Describe(Step step)
    {
        foreach (var key in new[] { "selector", "text", "url", "path" })
            if (step.Raw.TryGetValue(key, out var el) && el.ValueKind == System.Text.Json.JsonValueKind.String)
                return $"{step.Type} {el.GetString()}".Trim();
        return step.Type;
    }

    private async Task<string?> CaptureFailureAsync(Automation automation, int index)
    {
        if (Web.Page is null) return null;
        var path = $"failure_{Slug(automation.Name)}_{index}.png";
        try
        {
            await Web.Page.ScreenshotAsync(new() { Path = path });
            return path;
        }
        catch { return null; }
    }

    private static string Slug(string text) =>
        new string(text.Select(c => char.IsLetterOrDigit(c) ? char.ToLowerInvariant(c) : '-').ToArray())
            .Trim('-');

    public async ValueTask DisposeAsync()
    {
        await Web.DisposeAsync();
        Native.Dispose();
    }

    // Exposed for tests / callers that want the routing sets.
    public static bool IsKnownStep(string type) =>
        WebSteps.Contains(type) || NativeSteps.Contains(type) || GenericSteps.Contains(type);
}
