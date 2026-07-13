using System.Text.Json;
using AutoFlow.Core;
using AutoFlow.Core.Drivers;
using Xunit;

namespace AutoFlow.Tests;

public class EngineTests
{
    // Locate desktop/automations by walking up from the test assembly location.
    private static string DesktopDir()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            var candidate = Path.Combine(dir.FullName, "automations");
            if (Directory.Exists(candidate) && Directory.Exists(Path.Combine(dir.FullName, "schema")))
                return dir.FullName;
            dir = dir.Parent;
        }
        throw new DirectoryNotFoundException("could not locate the 'desktop' dir from " + AppContext.BaseDirectory);
    }

    private static string AutomationsDir() => Path.Combine(DesktopDir(), "automations");

    [Fact]
    public void Resolve_substitutes_known_tokens_only()
    {
        var vars = new Dictionary<string, string> { ["username"] = "Nga1p" };
        Assert.Equal("hi Nga1p!", Tokens.Resolve("hi {username}!", vars));
        Assert.Equal("{missing}", Tokens.Resolve("{missing}", new Dictionary<string, string>()));
        Assert.Null(Tokens.Resolve(null, vars));
    }

    [Fact]
    public void All_shipped_automations_parse()
    {
        var files = Directory.GetFiles(AutomationsDir(), "*.json");
        Assert.NotEmpty(files);
        foreach (var f in files)
        {
            var a = Automation.Load(f);
            Assert.False(string.IsNullOrEmpty(a.Name), $"{Path.GetFileName(f)} has no name");
            Assert.NotNull(a.Steps);
        }
    }

    [Fact]
    public void Selftest_shape()
    {
        var a = Automation.Load(Path.Combine(AutomationsDir(), "_selftest.json"));
        var types = a.Steps.Select(s => s.Type).ToList();
        Assert.Equal("open_url", types[0]);
        Assert.Contains("ensure_checked", types);
        Assert.Contains("verify", types);
    }

    [Fact]
    public async Task Engine_retries_then_succeeds()
    {
        var flaky = new FlakyGeneric(failuresBeforeSuccess: 2);
        await using var engine = new Engine(generic: flaky);
        var auto = new Automation
        {
            Name = "t",
            Steps = { Sleep(attempts: 3, backoffMs: 0) },
        };
        var result = await engine.RunAsync(auto);
        Assert.True(result.Ok);
        Assert.Equal(3, result.Steps[0].Attempts);
    }

    [Fact]
    public async Task Engine_fails_after_exhausting_retries()
    {
        var always = new FlakyGeneric(failuresBeforeSuccess: 99);
        await using var engine = new Engine(generic: always);
        var auto = new Automation
        {
            Name = "t",
            Steps = { Sleep(attempts: 2, backoffMs: 0) },
        };
        var result = await engine.RunAsync(auto);
        Assert.False(result.Ok);
        Assert.Equal(2, result.Steps[0].Attempts);
    }

    [Fact]
    public async Task Unknown_step_type_fails_run()
    {
        await using var engine = new Engine();
        var auto = new Automation
        {
            Name = "t",
            Steps = { new Step("bogus", new RetryPolicy(1, 0), new Dictionary<string, JsonElement>()) },
        };
        var result = await engine.RunAsync(auto);
        Assert.False(result.Ok);
    }

    [Fact]
    public async Task Engine_reports_progress_events()
    {
        var events = new List<StepEvent>();
        var collector = new SyncProgress(events.Add);
        await using var engine = new Engine(progress: collector);
        var auto = new Automation { Name = "t", Steps = { Sleep(attempts: 1, backoffMs: 0) } };

        var result = await engine.RunAsync(auto);

        Assert.True(result.Ok);
        Assert.Equal(StepPhase.Started, events[0].Phase);
        Assert.Equal(StepPhase.Succeeded, events[^1].Phase);
        Assert.Equal(0, events[0].Index);
        Assert.Equal(1, events[0].Total);
    }

    [Fact]
    public void Automations_match_schema_types()
    {
        var schemaPath = Path.Combine(DesktopDir(), "schema", "automation.schema.json");
        using var doc = JsonDocument.Parse(File.ReadAllText(schemaPath));
        var allowed = doc.RootElement
            .GetProperty("$defs").GetProperty("step").GetProperty("properties")
            .GetProperty("type").GetProperty("enum")
            .EnumerateArray().Select(e => e.GetString()).ToHashSet();

        foreach (var f in Directory.GetFiles(AutomationsDir(), "*.json"))
            foreach (var step in Automation.Load(f).Steps)
                Assert.True(allowed.Contains(step.Type),
                    $"{Path.GetFileName(f)}: {step.Type} not in schema");
    }

    // --- helpers ------------------------------------------------------------

    private static Step Sleep(int attempts, int backoffMs) =>
        new("sleep", new RetryPolicy(attempts, backoffMs),
            new Dictionary<string, JsonElement> { ["ms"] = JsonDocument.Parse("0").RootElement.Clone() });

    private sealed class FlakyGeneric : GenericDriver
    {
        private readonly int _failuresBeforeSuccess;
        private int _calls;
        public FlakyGeneric(int failuresBeforeSuccess) => _failuresBeforeSuccess = failuresBeforeSuccess;

        public override Task Sleep(Params p)
        {
            _calls++;
            if (_calls <= _failuresBeforeSuccess)
                throw new InvalidOperationException("boom");
            return Task.CompletedTask;
        }
    }

    // Synchronous IProgress so the test observes events deterministically
    // (Progress<T> would marshal asynchronously to a captured context).
    private sealed class SyncProgress : IProgress<StepEvent>
    {
        private readonly Action<StepEvent> _sink;
        public SyncProgress(Action<StepEvent> sink) => _sink = sink;
        public void Report(StepEvent value) => _sink(value);
    }
}
