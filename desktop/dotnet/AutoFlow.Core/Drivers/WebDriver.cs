using Microsoft.Playwright;

namespace AutoFlow.Core.Drivers;

/// <summary>
/// Browser driver — real DOM automation via Playwright (async C# API). Mirrors
/// the Python WebDriver: created lazily on first open_url so native-only flows
/// never launch a browser.
/// </summary>
public sealed class WebDriver : IAsyncDisposable
{
    private readonly bool _headed;
    private IPlaywright? _pw;
    private IBrowser? _browser;
    private IBrowserContext? _context;

    public IPage? Page { get; private set; }

    public WebDriver(bool headed = true) => _headed = headed;

    // --- lifecycle ----------------------------------------------------------

    private async Task<IPage> EnsurePageAsync(string? browser)
    {
        if (Page is not null) return Page;
        _pw = await Playwright.CreateAsync();

        var opts = new BrowserTypeLaunchOptions { Headless = !_headed };
        try
        {
            _browser = browser switch
            {
                "chrome" or "msedge" => await _pw.Chromium.LaunchAsync(
                    new BrowserTypeLaunchOptions { Headless = !_headed, Channel = browser }),
                "firefox" => await _pw.Firefox.LaunchAsync(opts),
                _ => await _pw.Chromium.LaunchAsync(opts),
            };
        }
        catch
        {
            // Requested channel not installed — fall back to bundled Chromium.
            _browser = await _pw.Chromium.LaunchAsync(new BrowserTypeLaunchOptions { Headless = !_headed });
        }

        _context = await _browser.NewContextAsync();
        Page = await _context.NewPageAsync();
        return Page;
    }

    public async ValueTask DisposeAsync()
    {
        try { if (_browser is not null) await _browser.CloseAsync(); }
        finally { _pw?.Dispose(); }
    }

    // --- helpers ------------------------------------------------------------

    private ILocator Locate(Params p)
    {
        var selector = p.Str("selector");
        var exact = p.Bool("exact", false);
        if (!string.IsNullOrEmpty(selector))
            return Page!.Locator(selector).First;
        if (p.Has("text"))
            return Page!.GetByText(p.Str("text") ?? "", new() { Exact = exact }).First;
        throw new InvalidOperationException("step needs 'selector' or 'text'");
    }

    // --- steps --------------------------------------------------------------

    public async Task OpenUrl(Params p)
    {
        var page = await EnsurePageAsync(p.Str("browser"));
        await page.GotoAsync(p.Str("url") ?? "",
            new PageGotoOptions { WaitUntil = WaitUntilState.DOMContentLoaded });
    }

    public async Task WaitFor(Params p)
    {
        var selector = p.Str("selector");
        var sel = !string.IsNullOrEmpty(selector) ? selector : $"text={p.Str("text")}";
        await Page!.WaitForSelectorAsync(sel, new() { Timeout = p.Int("timeoutMs", 8000) });
    }

    public async Task Fill(Params p)
    {
        var label = p.Str("label");
        var loc = !string.IsNullOrEmpty(label)
            ? Page!.GetByLabel(label).First
            : Page!.Locator(p.Str("selector") ?? "").First;
        await loc.FillAsync(p.Str("text") ?? "");
    }

    public async Task Click(Params p) => await Locate(p).ClickAsync();

    public async Task EnsureChecked(Params p)
    {
        var loc = Locate(p);
        var want = p.Bool("checked", true);
        if (await loc.IsCheckedAsync() != want)
            await loc.SetCheckedAsync(want);
    }

    public async Task ClickConfirm(Params p)
    {
        int attempts = Math.Max(1, p.Int("attempts", 3));
        int gapMs = p.Int("gapMs", 4000);
        var confirmText = p.Str("confirmText");

        for (int i = 0; i < attempts; i++)
        {
            var loc = Locate(p);
            if (await loc.CountAsync() == 0) break; // target gone (already submitted)
            try { await loc.ClickAsync(new() { Timeout = 3000 }); } catch { /* keep trying */ }

            if (!string.IsNullOrEmpty(confirmText))
            {
                try
                {
                    await Page!.WaitForSelectorAsync($"text={confirmText}", new() { Timeout = gapMs });
                    return;
                }
                catch { /* not confirmed yet — retry */ }
            }
            else
            {
                await Task.Delay(gapMs);
            }
        }
    }

    public async Task Verify(Params p)
    {
        var loc = Locate(p);
        bool present = p.Bool("present", true);
        bool found = await loc.CountAsync() > 0 && await loc.IsVisibleAsync();
        if (found != present)
        {
            var what = p.Str("selector") ?? p.Str("text");
            throw new Exception($"verify failed: {(present ? "expected" : "did not expect")} '{what}'");
        }
    }

    public async Task Press(Params p) => await Page!.Keyboard.PressAsync(p.Str("key") ?? "Enter");

    public async Task Reload(Params p) =>
        await Page!.ReloadAsync(new() { WaitUntil = WaitUntilState.DOMContentLoaded });

    public async Task Screenshot(Params p) =>
        await Page!.ScreenshotAsync(new() { Path = p.Str("path") ?? "autoflow-screenshot.png" });

    public async Task ClearSiteData(Params p)
    {
        await _context!.ClearCookiesAsync();
        try { await Page!.EvaluateAsync("() => { localStorage.clear(); sessionStorage.clear(); }"); }
        catch { /* origin may not permit storage access */ }
    }
}
