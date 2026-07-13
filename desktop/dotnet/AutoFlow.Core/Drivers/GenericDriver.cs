using System.Drawing;
using FlaUI.Core.Input;

namespace AutoFlow.Core.Drivers;

/// <summary>
/// OS-agnostic + coordinate/keyboard steps. Methods are virtual so tests can
/// substitute a flaky driver (mirrors the Python tests monkey-patching sleep).
/// Coordinate/keyboard steps use FlaUI input (Windows-only at runtime).
/// </summary>
public class GenericDriver
{
    public virtual Task Sleep(Params p) => Task.Delay(Math.Max(0, p.Int("ms", 1000)));

    public virtual async Task Manual(Params p)
    {
        var message = p.Str("message") ?? "Do the manual step, then continue…";
        int timeoutMs = p.Int("timeoutMs", 20000);
        Console.WriteLine();
        Console.WriteLine($"  [manual] {message}");
        Console.WriteLine($"  Waiting up to {timeoutMs / 1000}s (press Enter to continue sooner)…");
        await WaitOrEnter(timeoutMs);
    }

    public virtual Task ClickXy(Params p)
    {
        Mouse.MoveTo(new Point(p.Int("x", 0), p.Int("y", 0)));
        Mouse.Click(MouseButton.Left);
        return Task.CompletedTask;
    }

    public virtual Task TypeText(Params p)
    {
        Keyboard.Type(p.Str("text") ?? "");
        return Task.CompletedTask;
    }

    /// <summary>Wait up to timeoutMs, returning early if the user presses Enter.</summary>
    private static async Task WaitOrEnter(int timeoutMs)
    {
        var enter = Task.Run(() => Console.ReadLine());
        await Task.WhenAny(enter, Task.Delay(timeoutMs));
    }
}
