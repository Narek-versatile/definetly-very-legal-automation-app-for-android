using System.Runtime.Versioning;
using FlaUI.Core;
using FlaUI.Core.AutomationElements;
using FlaUI.Core.Conditions;
using FlaUI.Core.Definitions;
using FlaUI.Core.Tools;
using FlaUI.UIA3;

namespace AutoFlow.Core.Drivers;

/// <summary>
/// Native Windows-app driver via UI Automation (FlaUI / UIA3). Mirrors the
/// Python pywinauto NativeDriver. Windows-only — guarded so a non-Windows run
/// fails loudly instead of misbehaving.
/// </summary>
public sealed class NativeDriver : IDisposable
{
    private UIA3Automation? _automation;
    private Application? _app;
    private Window? _window;

    private static void RequireWindows()
    {
        if (!OperatingSystem.IsWindows())
            throw new PlatformNotSupportedException("native steps require Windows");
    }

    [SupportedOSPlatform("windows")]
    private UIA3Automation Automation => _automation ??= new UIA3Automation();

    public Task Launch(Params p)
    {
        RequireWindows();
        var path = p.Str("path") ?? "";
        var args = string.Join(' ', p.StrArray("args"));
        _app = string.IsNullOrEmpty(args) ? Application.Launch(path) : Application.Launch(path, args);
        return Task.CompletedTask;
    }

    [SupportedOSPlatform("windows")]
    public Task FocusWindow(Params p)
    {
        RequireWindows();
        var title = p.Str("title") ?? "";

        _window = Retry.WhileNull(() =>
        {
            // Prefer the launched app's own window; fall back to a desktop-wide search.
            if (_app is not null)
            {
                var main = _app.GetMainWindow(Automation);
                if (main is not null && main.Title.Contains(title, StringComparison.OrdinalIgnoreCase))
                    return main;
            }
            var desktop = Automation.GetDesktop();
            return desktop.FindAllChildren()
                .FirstOrDefault(e => e.ControlType == ControlType.Window
                                     && (e.Name?.Contains(title, StringComparison.OrdinalIgnoreCase) ?? false))
                ?.AsWindow();
        }, TimeSpan.FromSeconds(10), TimeSpan.FromMilliseconds(300)).Result;

        if (_window is null)
            throw new InvalidOperationException($"window not found: '{title}'");
        _window.Focus();
        return Task.CompletedTask;
    }

    [SupportedOSPlatform("windows")]
    public Task UiClick(Params p)
    {
        RequireWindows();
        Find(p).Click();
        return Task.CompletedTask;
    }

    [SupportedOSPlatform("windows")]
    public Task UiFill(Params p)
    {
        RequireWindows();
        var el = Find(p);
        var tb = el.AsTextBox();
        if (tb is not null) tb.Text = p.Str("text") ?? "";
        else el.Focus();
        return Task.CompletedTask;
    }

    [SupportedOSPlatform("windows")]
    public Task CloseWindow(Params p)
    {
        RequireWindows();
        var title = p.Str("title") ?? "";
        var win = _window ?? Automation.GetDesktop().FindAllChildren()
            .FirstOrDefault(e => e.ControlType == ControlType.Window
                                 && (e.Name?.Contains(title, StringComparison.OrdinalIgnoreCase) ?? false))
            ?.AsWindow();
        win?.Close();
        return Task.CompletedTask;
    }

    [SupportedOSPlatform("windows")]
    private AutomationElement Find(Params p)
    {
        var root = (AutomationElement?)_window ?? Automation.GetDesktop();
        var name = p.Str("name");
        var automationId = p.Str("automationId");
        var controlType = p.Str("controlType");

        var el = Retry.WhileNull(() => root.FindFirstDescendant(cf =>
        {
            ConditionBase? cond = null;
            if (!string.IsNullOrEmpty(name)) cond = cf.ByName(name);
            if (!string.IsNullOrEmpty(automationId))
                cond = cond is null ? cf.ByAutomationId(automationId) : cond.And(cf.ByAutomationId(automationId));
            if (!string.IsNullOrEmpty(controlType) && Enum.TryParse<ControlType>(controlType, true, out var ct))
                cond = cond is null ? cf.ByControlType(ct) : cond.And(cf.ByControlType(ct));
            return cond ?? cf.ByControlType(ControlType.Custom);
        }), TimeSpan.FromSeconds(10), TimeSpan.FromMilliseconds(300)).Result;

        if (el is null)
            throw new InvalidOperationException($"element not found: name='{name}' id='{automationId}'");
        return el;
    }

    public void Dispose()
    {
        _automation?.Dispose();
        _app?.Dispose();
    }
}
