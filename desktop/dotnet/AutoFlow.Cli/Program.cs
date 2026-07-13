using AutoFlow.Core;

namespace AutoFlow.Cli;

/// <summary>
///   autoflow run &lt;file.json&gt; [--var k=v ...] [--headless] [--keep-open]
///   autoflow list &lt;dir&gt;
/// Same verbs and JSON files as the Python CLI.
/// </summary>
public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        if (args.Length == 0)
        {
            Usage();
            return 2;
        }

        return args[0] switch
        {
            "run" => await CmdRun(args.Skip(1).ToArray()),
            "list" => CmdList(args.Skip(1).ToArray()),
            _ => Usage(),
        };
    }

    private static async Task<int> CmdRun(string[] args)
    {
        string? file = null;
        var vars = new Dictionary<string, string>();
        bool headed = true;
        bool keepOpen = false;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--var":
                    if (i + 1 >= args.Length || !args[++i].Contains('='))
                    {
                        Console.Error.WriteLine("--var expects k=v");
                        return 2;
                    }
                    var kv = args[i].Split('=', 2);
                    vars[kv[0]] = kv[1];
                    break;
                case "--headless": headed = false; break;
                case "--headed": headed = true; break;
                case "--keep-open": keepOpen = true; break;
                default:
                    file ??= args[i];
                    break;
            }
        }

        if (file is null)
        {
            Console.Error.WriteLine("run: missing <file.json>");
            return 2;
        }

        var automation = Automation.Load(file);
        Console.WriteLine($"Running: {automation.Name}");

        var engine = new Engine(vars, headed);
        RunResult result;
        try
        {
            result = await engine.RunAsync(automation);
        }
        finally
        {
            if (!keepOpen) await engine.DisposeAsync();
        }

        int passed = result.Steps.Count(s => s.Ok);
        var tail = result.Screenshot is null ? "" : $"  (screenshot: {result.Screenshot})";
        Console.WriteLine();
        Console.WriteLine($"{(result.Ok ? "OK" : "FAILED")} — {passed}/{result.Steps.Count} steps{tail}");
        return result.Ok ? 0 : 1;
    }

    private static int CmdList(string[] args)
    {
        if (args.Length == 0)
        {
            Console.Error.WriteLine("list: missing <dir>");
            return 2;
        }
        foreach (var path in Directory.GetFiles(args[0], "*.json").OrderBy(p => p))
        {
            try
            {
                var a = Automation.Load(path);
                Console.WriteLine($"{Path.GetFileName(path),-40} {a.Name}");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"{Path.GetFileName(path),-40} <invalid: {ex.Message}>");
            }
        }
        return 0;
    }

    private static int Usage()
    {
        Console.Error.WriteLine("usage:");
        Console.Error.WriteLine("  autoflow run <file.json> [--var k=v ...] [--headless] [--keep-open]");
        Console.Error.WriteLine("  autoflow list <dir>");
        return 2;
    }
}
