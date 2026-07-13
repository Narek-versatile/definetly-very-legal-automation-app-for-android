using AutoFlow.Core;
using AutoFlow.Core.Drivers;

namespace AutoFlow.Gui;

/// <summary>
/// Generic driver for the GUI: a `manual` step opens a dialog and waits for the
/// user instead of reading from a console. Everything else (sleep, click_xy,
/// type_text) uses the base behaviour.
/// </summary>
public sealed class GuiGenericDriver : GenericDriver
{
    private readonly Func<string, int, Task> _showManual;

    public GuiGenericDriver(Func<string, int, Task> showManual) => _showManual = showManual;

    public override Task Manual(Params p) =>
        _showManual(p.Str("message") ?? "Complete the manual step, then click Continue.",
                    p.Int("timeoutMs", 20000));
}
