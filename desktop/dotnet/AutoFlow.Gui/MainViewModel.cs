using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows.Media;
using AutoFlow.Core;

namespace AutoFlow.Gui;

/// <summary>Bindable state for the main window. Orchestration lives in the
/// code-behind (MainWindow) so it can drive dialogs directly.</summary>
public sealed class MainViewModel : INotifyPropertyChanged
{
    public ObservableCollection<AutomationItem> Automations { get; } = new();
    public ObservableCollection<VarRow> Variables { get; } = new();
    public ObservableCollection<StepRow> Steps { get; } = new();

    private string _automationsFolder = "";
    public string AutomationsFolder
    {
        get => _automationsFolder;
        set { _automationsFolder = value; OnPropertyChanged(); }
    }

    private AutomationItem? _selected;
    public AutomationItem? SelectedAutomation
    {
        get => _selected;
        set { _selected = value; OnPropertyChanged(); OnSelectionChanged(); }
    }

    private bool _headless;
    public bool Headless
    {
        get => _headless;
        set { _headless = value; OnPropertyChanged(); }
    }

    private bool _isRunning;
    public bool IsRunning
    {
        get => _isRunning;
        set { _isRunning = value; OnPropertyChanged(); OnPropertyChanged(nameof(CanRun)); }
    }

    public bool CanRun => !_isRunning;

    private string _log = "";
    public string Log
    {
        get => _log;
        set { _log = value; OnPropertyChanged(); }
    }

    private string _resultText = "";
    public string ResultText
    {
        get => _resultText;
        set { _resultText = value; OnPropertyChanged(); }
    }

    private Brush _resultBrush = Brushes.Transparent;
    public Brush ResultBrush
    {
        get => _resultBrush;
        set { _resultBrush = value; OnPropertyChanged(); }
    }

    // --- behaviour ----------------------------------------------------------

    public void LoadAutomations(string folder)
    {
        AutomationsFolder = folder;
        Automations.Clear();
        if (!Directory.Exists(folder)) return;
        foreach (var path in Directory.GetFiles(folder, "*.json").OrderBy(p => p))
        {
            try
            {
                var a = Automation.Load(path);
                Automations.Add(new AutomationItem(Path.GetFileName(path), a.Name, path, a.Description));
            }
            catch
            {
                // Skip files that don't parse; the CLI's `list` shows these too.
            }
        }
    }

    private void OnSelectionChanged()
    {
        Variables.Clear();
        Steps.Clear();
        ResultText = "";
        if (SelectedAutomation is null) return;

        var automation = Automation.Load(SelectedAutomation.Path);
        foreach (var kv in automation.Vars)
            Variables.Add(new VarRow { Key = kv.Key, Value = kv.Value });
        // Always expose a username field even if the automation didn't declare one.
        if (!automation.Vars.ContainsKey("username"))
            Variables.Add(new VarRow { Key = "username", Value = "" });

        for (int i = 0; i < automation.Steps.Count; i++)
            Steps.Add(new StepRow { Number = i + 1, Describe = automation.Steps[i].Type, Status = "pending" });
    }

    public void AppendLog(string line) => Log += line + Environment.NewLine;

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

public sealed record AutomationItem(string FileName, string Name, string Path, string Description);

public sealed class VarRow
{
    public string Key { get; set; } = "";
    public string Value { get; set; } = "";
}

public sealed class StepRow : INotifyPropertyChanged
{
    public int Number { get; set; }

    private string _describe = "";
    public string Describe
    {
        get => _describe;
        set { _describe = value; OnPropertyChanged(); }
    }

    private string _status = "pending";
    public string Status
    {
        get => _status;
        set { _status = value; OnPropertyChanged(); OnPropertyChanged(nameof(StatusBrush)); }
    }

    public Brush StatusBrush => _status switch
    {
        "ok" => Brushes.SeaGreen,
        "failed" => Brushes.IndianRed,
        "running" => Brushes.SteelBlue,
        "retry" => Brushes.DarkOrange,
        _ => Brushes.Gray,
    };

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}
