using System.Windows;
using System.Windows.Threading;

namespace AutoFlow.Gui;

/// <summary>Blocking prompt for a `manual` step (e.g. solve a captcha). Closes
/// when the user clicks Continue or the timeout elapses.</summary>
public partial class ManualPromptWindow : Window
{
    private readonly DispatcherTimer _timer;
    private int _remaining;

    public ManualPromptWindow(string message, int timeoutMs)
    {
        InitializeComponent();
        MessageText.Text = message;
        _remaining = Math.Max(0, timeoutMs) / 1000;
        UpdateCountdown();

        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _timer.Tick += (_, _) =>
        {
            _remaining--;
            if (_remaining <= 0)
            {
                _timer.Stop();
                Close();
            }
            else
            {
                UpdateCountdown();
            }
        };
        if (_remaining > 0) _timer.Start();
    }

    private void UpdateCountdown() =>
        CountdownText.Text = $"Auto-continues in {_remaining}s — or click Continue when you're done.";

    private void Continue_Click(object sender, RoutedEventArgs e)
    {
        _timer.Stop();
        Close();
    }
}
