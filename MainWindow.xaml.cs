using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using ScriptureMemory.Models;
using ScriptureMemory.Services;
using Windows.System;

namespace ScriptureMemory;

public sealed partial class MainWindow : Window
{
    private readonly AppData _data;
    private VerseCollection? _current;

    public MainWindow()
    {
        InitializeComponent();
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1280, 860));
        var icon = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
        if (File.Exists(icon)) AppWindow.SetIcon(icon);

        _data = DataStore.Load();

        BookBox.ItemsSource = Bible.Books;
        BookBox.SelectedIndex = Bible.Books.ToList().FindIndex(b => b.Name == "John");

        _data.VoiceProfile ??= new VoiceProfile();
        PracticePanel.Profile = _data.VoiceProfile;
        PracticePanel.ProfileChanged += (_, _) => Save();
        PracticePanel.BackRequested += (_, _) => ShowCollection();
        PracticePanel.PracticeCompleted += (_, passage) =>
        {
            passage.PracticeCount++;
            passage.LastPracticed = DateTime.Now;
            Save();
        };
        Closed += (_, _) => PracticePanel.Shutdown();

        RefreshCollections();
        if (_data.Collections.Count > 0) CollectionsList.SelectedIndex = 0;
    }

    private void Save()
    {
        try
        {
            DataStore.Save(_data);
        }
        catch (Exception ex)
        {
            ShowStatus("Couldn't save your data: " + ex.Message, InfoBarSeverity.Error);
        }
    }

    // ---------- Collections ----------

    private void RefreshCollections(VerseCollection? select = null)
    {
        CollectionsList.ItemsSource = null;
        CollectionsList.ItemsSource = _data.Collections;
        if (select != null) CollectionsList.SelectedItem = select;
    }

    private void AddCollection_Click(object sender, RoutedEventArgs e) => AddCollection();

    private void NewCollectionBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Enter) AddCollection();
    }

    private void AddCollection()
    {
        var name = NewCollectionBox.Text.Trim();
        if (name.Length == 0)
        {
            NewCollectionBox.Focus(FocusState.Programmatic);
            return;
        }
        var collection = new VerseCollection { Name = name };
        _data.Collections.Add(collection);
        Save();
        NewCollectionBox.Text = "";
        RefreshCollections(collection);
    }

    private void CollectionsList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        _current = CollectionsList.SelectedItem as VerseCollection;
        RenameButton.IsEnabled = DeleteButton.IsEnabled = _current != null;
        StatusBar.IsOpen = false;
        ShowCollection();
    }

    private async void RenameCollection_Click(object sender, RoutedEventArgs e)
    {
        if (_current == null) return;
        var box = new TextBox { Text = _current.Name };
        box.SelectAll();
        var dialog = new ContentDialog
        {
            XamlRoot = Content.XamlRoot,
            Title = "Rename collection",
            Content = box,
            PrimaryButtonText = "Rename",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary && box.Text.Trim().Length > 0)
        {
            _current.Name = box.Text.Trim();
            Save();
            RefreshCollections(_current);
        }
    }

    private async void DeleteCollection_Click(object sender, RoutedEventArgs e)
    {
        if (_current == null) return;
        if (!await ConfirmAsync("Delete collection?",
                $"“{_current.Name}” and its {_current.Passages.Count} passage(s) will be removed.", "Delete"))
            return;
        _data.Collections.Remove(_current);
        Save();
        RefreshCollections();
        if (_data.Collections.Count > 0) CollectionsList.SelectedIndex = 0;
    }

    // ---------- Collection detail ----------

    private void ShowCollection()
    {
        PracticePanel.Shutdown();
        PracticePanel.Visibility = Visibility.Collapsed;

        if (_current == null)
        {
            CollectionPanel.Visibility = Visibility.Collapsed;
            EmptyState.Visibility = Visibility.Visible;
            return;
        }

        EmptyState.Visibility = Visibility.Collapsed;
        CollectionPanel.Visibility = Visibility.Visible;
        CollectionTitle.Text = _current.Name;

        int count = _current.Passages.Count;
        int mastered = _current.Passages.Count(p => p.Mastered);
        CollectionSummary.Text = count == 0
            ? "No passages yet. Add some verses below."
            : $"{count} passage{(count == 1 ? "" : "s")} · {mastered} mastered";

        PassagesList.ItemsSource = null;
        PassagesList.ItemsSource = _current.Passages;
    }

    private void BookBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (BookBox.SelectedItem is Book book)
        {
            ChapterBox.Maximum = book.Chapters;
            if (ChapterBox.Value > book.Chapters) ChapterBox.Value = 1;
        }
    }

    private async void AddFromPicker_Click(object sender, RoutedEventArgs e)
    {
        if (BookBox.SelectedItem is not Book book) return;
        if (double.IsNaN(ChapterBox.Value))
        {
            ShowStatus("Enter a chapter number.", InfoBarSeverity.Warning);
            return;
        }

        int chapter = (int)ChapterBox.Value;
        var query = $"{book.Name} {chapter}";
        if (!double.IsNaN(FromVerseBox.Value))
        {
            int from = (int)FromVerseBox.Value;
            query += $":{from}";
            if (!double.IsNaN(ToVerseBox.Value) && (int)ToVerseBox.Value > from)
                query += $"-{(int)ToVerseBox.Value}";
        }
        await AddPassageAsync(query);
    }

    private async void AddFromReference_Click(object sender, RoutedEventArgs e)
    {
        var query = ReferenceBox.Text.Trim();
        if (query.Length == 0) return;
        if (await AddPassageAsync(query)) ReferenceBox.Text = "";
    }

    private void ReferenceBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Enter) AddFromReference_Click(sender, e);
    }

    private async Task<bool> AddPassageAsync(string query)
    {
        if (_current == null) return false;

        var apiKey = DataStore.GetApiKey(_data);
        if (apiKey.Length == 0)
        {
            await ShowSettingsAsync();
            apiKey = DataStore.GetApiKey(_data);
            if (apiKey.Length == 0) return false;
        }

        SetBusy(true);
        try
        {
            var (canonical, text) = await EsvClient.GetPassageAsync(apiKey, query);
            if (_current.Passages.Any(p => p.Reference == canonical))
            {
                ShowStatus($"{canonical} is already in this collection.", InfoBarSeverity.Warning);
                return false;
            }
            _current.Passages.Add(new Passage { Reference = canonical, Query = query, Text = text });
            Save();
            ShowCollection();
            ShowStatus($"Added {canonical}.", InfoBarSeverity.Success);
            return true;
        }
        catch (EsvException ex)
        {
            ShowStatus(ex.Message, InfoBarSeverity.Error);
            return false;
        }
        catch (Exception ex)
        {
            ShowStatus("Something went wrong: " + ex.Message, InfoBarSeverity.Error);
            return false;
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void SetBusy(bool busy)
    {
        BusyPanel.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        AddPickerButton.IsEnabled = AddReferenceButton.IsEnabled = !busy;
    }

    private void ShowStatus(string message, InfoBarSeverity severity)
    {
        StatusBar.Message = message;
        StatusBar.Severity = severity;
        StatusBar.IsOpen = true;
    }

    // ---------- Passage actions ----------

    private void PassagesList_ItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is Passage p) OpenPractice(p);
    }

    private void Practice_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is Passage p) OpenPractice(p);
    }

    private void Mastered_Click(object sender, RoutedEventArgs e)
    {
        if (sender is CheckBox { DataContext: Passage p } cb)
        {
            p.Mastered = cb.IsChecked == true;
            Save();
            ShowCollection();
        }
    }

    private async void DeletePassage_Click(object sender, RoutedEventArgs e)
    {
        if (_current == null || (sender as FrameworkElement)?.DataContext is not Passage p) return;
        if (!await ConfirmAsync("Remove passage?", $"Remove {p.Reference} from “{_current.Name}”?", "Remove")) return;
        _current.Passages.Remove(p);
        Save();
        ShowCollection();
    }

    private void OpenPractice(Passage passage)
    {
        CollectionPanel.Visibility = Visibility.Collapsed;
        PracticePanel.Visibility = Visibility.Visible;
        PracticePanel.Load(passage);
    }

    // ---------- Settings / dialogs ----------

    /// <summary>
    /// Opens the Windows speech training wizard. The training is saved to the Windows speech profile,
    /// which is the same recognizer the Speak feature uses.
    /// </summary>
    private async void TrainVoice_Click(object sender, RoutedEventArgs e)
    {
        var wizard = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "Speech", "SpeechUX", "SpeechUXWiz.exe");
        try
        {
            if (!File.Exists(wizard)) throw new FileNotFoundException();
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(wizard, "UserTraining") { UseShellExecute = true });
        }
        catch (Exception)
        {
            await new ContentDialog
            {
                XamlRoot = Content.XamlRoot,
                Title = "Voice training unavailable",
                Content = "The Windows speech training wizard wasn't found on this PC. Recognition still works without it.",
                CloseButtonText = "OK",
            }.ShowAsync();
        }
    }

    private async void Settings_Click(object sender, RoutedEventArgs e) => await ShowSettingsAsync();

    private async Task ShowSettingsAsync()
    {
        var keyBox = new PasswordBox
        {
            Header = "API key",
            Password = DataStore.GetApiKey(_data),
            PlaceholderText = "Paste your ESV API token",
        };
        var panel = new StackPanel { Spacing = 12, MinWidth = 420 };
        panel.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Text = "Scripture text comes from the ESV API, which needs a free API key. " +
                   "Sign in at api.esv.org, create an application, and paste its key here. " +
                   "The key is stored encrypted for your Windows account.",
        });
        panel.Children.Add(new HyperlinkButton
        {
            Content = "Get an ESV API key",
            NavigateUri = new Uri("https://api.esv.org/account/create-application/"),
        });
        panel.Children.Add(keyBox);

        var dialog = new ContentDialog
        {
            XamlRoot = Content.XamlRoot,
            Title = "ESV API key",
            Content = panel,
            PrimaryButtonText = "Save",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary)
        {
            DataStore.SetApiKey(_data, keyBox.Password);
            Save();
        }
    }

    private async Task<bool> ConfirmAsync(string title, string message, string confirmText)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = Content.XamlRoot,
            Title = title,
            Content = message,
            PrimaryButtonText = confirmText,
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
        };
        return await dialog.ShowAsync() == ContentDialogResult.Primary;
    }
}
