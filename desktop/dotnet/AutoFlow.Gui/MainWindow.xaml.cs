using System.IO;
using System.Windows;
using System.Windows.Media;
using AutoFlow.Core;

namespace AutoFlow.Gui;

public partial class MainWindow : Window
{
    private readonly MainViewModel _vm = new();

    public MainWindow()
    {
        InitializeComponent();
        DataContext = _vm;
        _vm.LoadAutomations(DefaultAutomationsFolder());
    }

    // --- toolbar ------------------------------------------------------------

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog { Title = "Select the automations folder" };
        if (dlg.ShowDialog() == true)
            _vm.LoadAutomations(dlg.FolderName);
    }

    private void Reload_Click(object sender, RoutedEventArgs e) =>
        _vm.LoadAutomations(_vm.AutomationsFolder);

    // --- run ----------------------------------------------------------------

    private async void Run_Click(object sender, RoutedEventArgs e)
    {
        if (_vm.SelectedAutomation is null)
        {
            MessageBox.Show("Pick an automation first.", "AutoFlow");
            return;
        }

        _vm.IsRunning = true;
        _vm.Log = "";
        _vm.ResultText = "";
        foreach (var s in _vm.Steps) s.Status = "pending";

        var automation = Automation.Load(_vm.SelectedAutomation.Path);
        var vars = _vm.Variables
            .Where(v => !string.IsNullOrWhiteSpace(v.Key))
            .GroupBy(v => v.Key)
            .ToDictionary(g => g.Key, g => g.Last().Value ?? "");

        // Created on the UI thread, so Report callbacks marshal back here.
        var progress = new Progress<StepEvent>(OnStepEvent);
        var manualDriver = new GuiGenericDriver(ShowManual);
        var engine = new Engine(vars, headed: !_vm.Headless, generic: manualDriver, progress: progress);

        RunResult result;
        try
        {
            result = await Task.Run(() => engine.RunAsync(automation));
        }
        catch (Exception ex)
        {
            _vm.AppendLog("ERROR: " + ex.Message);
            result = new RunResult { Name = automation.Name, Ok = false };
        }
        finally
        {
            await engine.DisposeAsync();
            _vm.IsRunning = false;
        }

        int passed = result.Steps.Count(s => s.Ok);
        if (result.Ok)
        {
            _vm.ResultText = $"OK — {passed}/{result.Steps.Count} steps";
            _vm.ResultBrush = Brushes.SeaGreen;
        }
        else
        {
            var shot = result.Screenshot is null ? "" : $"  (screenshot: {result.Screenshot})";
            _vm.ResultText = $"FAILED — {passed}/{result.Steps.Count} steps{shot}";
            _vm.ResultBrush = Brushes.IndianRed;
        }
    }

    private void OnStepEvent(StepEvent ev)
    {
        if (ev.Index >= 0 && ev.Index < _vm.Steps.Count)
        {
            var row = _vm.Steps[ev.Index];
            row.Describe = ev.Describe;
            row.Status = ev.Phase switch
            {
                StepPhase.Started => "running",
                StepPhase.Retrying => "retry",
                StepPhase.Succeeded => "ok",
                StepPhase.Failed => "failed",
                _ => row.Status,
            };
        }

        _vm.AppendLog(ev.Phase switch
        {
            StepPhase.Started => $"[{ev.Index + 1}/{ev.Total}] {ev.Describe}",
            StepPhase.Retrying => $"    retry {ev.Attempt}: {ev.Message}",
            StepPhase.Succeeded => "    ok",
            StepPhase.Failed => $"    x {ev.Message}",
            _ => "",
        });
    }

    /// <summary>Called from the engine's background thread for a `manual` step;
    /// marshals to the UI thread and blocks until the user continues or it times out.</summary>
    private Task ShowManual(string message, int timeoutMs)
    {
        Dispatcher.Invoke(() =>
        {
            var w = new ManualPromptWindow(message, timeoutMs) { Owner = this };
            w.ShowDialog();
        });
        return Task.CompletedTask;
    }

    // --- helpers ------------------------------------------------------------

    private static string DefaultAutomationsFolder()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            var candidate = Path.Combine(dir.FullName, "automations");
            if (Directory.Exists(candidate) && Directory.Exists(Path.Combine(dir.FullName, "schema")))
                return candidate;
            dir = dir.Parent;
        }
        return AppContext.BaseDirectory;
    }
}
