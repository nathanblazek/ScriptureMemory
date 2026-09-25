using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using ScriptureMemory.Models;
using ScriptureMemory.Practice;

namespace ScriptureMemory;

public sealed partial class PracticeView : UserControl
{
    /// <summary>How many already-passed words the reciter may back up and repeat.</summary>
    private const int LookBack = 6;

    /// <summary>How many words beyond what was actually spoken the alignment may reach (skipped words).</summary>
    private const int MaxSkipAhead = 2;

    private readonly List<WordToken> _tokens = new();
    private readonly List<WordView> _words = new();          // only Word tokens, in reading order
    private readonly List<TextBlock> _verseNumbers = new();
    private readonly List<ParagraphBreak> _breaks = new();

    /// <summary>Every speakable word part in reading order ("well-pleased" contributes two).</summary>
    private readonly List<(WordView View, string Key)> _parts = new();

    private Passage? _passage;
    private PracticeMode _mode = PracticeMode.Full;
    private SpeechPractice? _speech;

    private int _cursor;           // index into _parts of the next part the reciter should say
    private int? _utteranceStart;  // _cursor when the current utterance began
    private int _spoken, _peeked, _missed;
    private bool _completed;

    // Calibration (reading the visible text aloud)
    private bool _calibrating;
    private readonly HashSet<string> _calibratedKeys = new();

    public event EventHandler? BackRequested;
    public event EventHandler<Passage>? PracticeCompleted;

    /// <summary>Raised when calibration has updated <see cref="Profile"/> and it should be saved.</summary>
    public event EventHandler? ProfileChanged;

    /// <summary>The user's per-word voice calibration; set by the host window.</summary>
    public VoiceProfile? Profile { get; set; }

    public PracticeView()
    {
        InitializeComponent();
        Unloaded += (_, _) => StopSpeech();
    }

    private double WordFontSize => FontSizeSlider.Value;

    public void Load(Passage passage)
    {
        StopSpeech();
        _passage = passage;
        ReferenceText.Text = passage.Reference + " (ESV)";

        _tokens.Clear();
        _tokens.AddRange(Tokenizer.Tokenize(passage.Text));
        BuildWords();
        SetMode(PracticeMode.Full);
        Scroller.ChangeView(null, 0, null, true);
    }

    /// <summary>Stops the microphone; call when leaving the view or closing the window.</summary>
    public void Shutdown() => StopSpeech();

    // ---------- Building the text ----------

    private void BuildWords()
    {
        WordsPanel.Children.Clear();
        _words.Clear();
        _verseNumbers.Clear();
        _breaks.Clear();
        _parts.Clear();

        foreach (var token in _tokens)
        {
            switch (token.Kind)
            {
                case TokenKind.ParagraphBreak:
                    var br = new ParagraphBreak(WordFontSize * 0.7);
                    _breaks.Add(br);
                    WordsPanel.Children.Add(br);
                    break;

                case TokenKind.VerseNumber:
                    var num = new TextBlock
                    {
                        Text = token.Display,
                        FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                        Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["AccentTextFillColorPrimaryBrush"],
                        Margin = new Thickness(4, 0, -4, 0),
                    };
                    _verseNumbers.Add(num);
                    WordsPanel.Children.Add(num);
                    break;

                default:
                    var word = new WordView(token, WordFontSize);
                    word.Tapped += Word_Tapped;
                    _words.Add(word);
                    WordsPanel.Children.Add(word);
                    foreach (var key in token.MatchParts) _parts.Add((word, key));
                    break;
            }
        }
        ApplyFontSize();
    }

    private void ApplyFontSize()
    {
        var size = WordFontSize;
        foreach (var w in _words) w.SetFontSize(size);
        foreach (var n in _verseNumbers) n.FontSize = size * 0.55;
        foreach (var b in _breaks) b.Height = size * 0.7;
        WordsPanel.HorizontalSpacing = size * 0.28;
        WordsPanel.LineSpacing = size * 0.45;
        WordsPanel.InvalidateMeasure();
    }

    private void FontSize_Changed(object sender, RangeBaseValueChangedEventArgs e)
    {
        if (WordsPanel != null) ApplyFontSize();
    }

    // ---------- Modes ----------

    private void Full_Click(object sender, RoutedEventArgs e) => SetMode(PracticeMode.Full);
    private void FirstLetter_Click(object sender, RoutedEventArgs e) => SetMode(PracticeMode.FirstLetter);
    private void Blur_Click(object sender, RoutedEventArgs e) => SetMode(PracticeMode.Blur);
    private void Reset_Click(object sender, RoutedEventArgs e) => SetMode(_mode);

    private void SetMode(PracticeMode mode)
    {
        _mode = mode;
        FullButton.IsChecked = mode == PracticeMode.Full;
        FirstLetterButton.IsChecked = mode == PracticeMode.FirstLetter;
        BlurButton.IsChecked = mode == PracticeMode.Blur;

        foreach (var w in _words) w.SetMode(mode);
        _spoken = _peeked = _missed = 0;
        _cursor = 0;
        _utteranceStart = null;
        _completed = false;
        CompleteBar.IsOpen = false;

        MicButton.IsEnabled = mode != PracticeMode.Full;
        if (mode == PracticeMode.Full)
        {
            StopSpeech();
            SpeechStatus.Text = "Pick “First letters” or “Blur” to hide the words. Click any hidden word to peek at it.";
        }
        else if (_speech == null)
        {
            SpeechStatus.Text = "Click a hidden word to reveal it, or press Speak and recite the passage out loud.";
        }
        UpdateProgress();
    }

    // ---------- Revealing ----------

    private void Word_Tapped(object sender, TappedRoutedEventArgs e)
    {
        if (sender is not WordView word || !word.IsConcealed) return;
        word.RevealWord(RevealKind.Peeked);
        _peeked++;
        UpdateProgress();
    }

    /// <summary>The first still-hidden word at or after the cursor (what the reciter should say next).</summary>
    private WordView? CurrentWord()
    {
        for (int i = _cursor; i < _parts.Count; i++)
            if (_parts[i].View.IsConcealed) return _parts[i].View;
        return null;
    }

    private void UpdateProgress()
    {
        var current = _calibrating ? (_cursor < _parts.Count ? _parts[_cursor].View : null)
                    : _speech != null ? CurrentWord() : null;
        foreach (var w in _words) w.SetCurrent(ReferenceEquals(w, current));
        current?.StartBringIntoView(new BringIntoViewOptions { VerticalAlignmentRatio = 0.4, AnimationDesired = true });

        int total = _words.Count(w => w.Token.HasLetters);
        if (_calibrating)
        {
            StatsText.Text = $"Read {Math.Min(_cursor, _parts.Count)}/{_parts.Count} words";
            return;
        }
        if (_mode == PracticeMode.Full)
        {
            StatsText.Text = $"{total} words";
            return;
        }

        int hidden = _words.Count(w => w.IsConcealed);
        StatsText.Text = $"{total - hidden}/{total} revealed  ·  spoken {_spoken}  ·  peeked {_peeked}" +
                         (_missed > 0 ? $"  ·  missed {_missed}" : "");

        if (hidden == 0 && total > 0 && !_completed)
        {
            _completed = true;
            StopSpeech();
            CompleteBar.Title = "Passage complete!";
            CompleteBar.Message = _spoken > 0
                ? $"You recited {_spoken} of {total} words ({_spoken * 100 / total}%). Press Reset to go again."
                : "All words revealed. Press Reset to go again.";
            CompleteBar.IsOpen = true;
            if (_passage != null) PracticeCompleted?.Invoke(this, _passage);
        }
    }

    // ---------- Speech ----------

    private void Mic_Click(object sender, RoutedEventArgs e)
    {
        if (MicButton.IsChecked == true) StartSpeech();
        else StopSpeech();
    }

    private void StartSpeech()
    {
        if (_speech != null) return;

        // Pick up where the reciter left off.
        _cursor = _parts.FindIndex(p => p.View.IsConcealed);
        if (_cursor < 0) _cursor = _parts.Count;
        _utteranceStart = null;

        try
        {
            // Same order and count as _parts, so positions line up.
            var passageWords = _tokens.Where(t => t.IsSpeakable).SelectMany(t => t.SpeechParts).ToList();
            _speech = new SpeechPractice(passageWords, _cursor);
        }
        catch (Exception ex)
        {
            MicButton.IsChecked = false;
            SpeechStatus.Text = ex.Message;
            return;
        }

        _speech.Hypothesized += words => DispatcherQueue.TryEnqueue(() => OnHeard(new[] { words }, final: false));
        _speech.Recognized += candidates => DispatcherQueue.TryEnqueue(() => OnHeard(candidates, final: true));
        _speech.AudioLevel += level => DispatcherQueue.TryEnqueue(() => MicLevel.Value = level);
        _speech.Start();

        MicButton.IsChecked = true;
        MicButtonText.Text = "Listening…";
        MicLevel.Visibility = Visibility.Visible;
        SpeechStatus.Text = "Listening. Recite at your own pace; words appear as you say them.";
        UpdateProgress();
    }

    private void StopSpeech()
    {
        if (_calibrating)
        {
            // Leaving mid-calibration keeps what was read so far.
            FinishCalibration(save: true);
            return;
        }
        if (_speech != null)
        {
            _speech.Dispose();
            _speech = null;
        }
        _utteranceStart = null;
        MicButton.IsChecked = false;
        MicButtonText.Text = "Speak";
        MicLevel.Visibility = Visibility.Collapsed;
        foreach (var w in _words) w.SetCurrent(false);
    }

    /// <summary>
    /// Aligns what was heard in the current utterance against the passage starting where the
    /// utterance began. Hypotheses are re-aligned as they grow, so words appear while the user is
    /// still speaking; the final result also fills in words that were skipped over.
    /// </summary>
    private void OnHeard(IReadOnlyList<IReadOnlyList<HeardWord>> candidates, bool final)
    {
        if (_speech == null) return;

        int start = _utteranceStart ??= _cursor;
        if (final) _utteranceStart = null;
        if (candidates.Count == 0) return;

        SpeechStatus.Text = "Heard: " + string.Join(" ", candidates[0].Select(w => w.Text));

        var match = AlignHeard(candidates, start, _calibrating ? null : Profile);
        if (match == null) return;

        if (_calibrating) ApplyCalibration(match.Value, start, final);
        else ApplyPractice(match.Value, start, final);

        // Point the recognizer at what should come next (takes effect for the next utterance).
        if (final) _speech?.SetExpectedPosition(_cursor);
        UpdateProgress();

        if (_calibrating && final && _cursor >= _parts.Count) FinishCalibration(save: true);
    }

    private readonly record struct HeardMatch(Aligner.Alignment Alignment, int WindowStart, IReadOnlyList<float> Confidence)
    {
        public int PartIndex(int target) => WindowStart + target;
        public int LastPart => WindowStart + Alignment.LastMatchedTarget;
    }

    /// <summary>
    /// Aligns what was heard in the current utterance against the passage starting where the
    /// utterance began, choosing whichever of the recognizer's guesses fits the passage best.
    /// </summary>
    private HeardMatch? AlignHeard(IReadOnlyList<IReadOnlyList<HeardWord>> candidates, int start, VoiceProfile? profile)
    {
        int windowStart = Math.Max(0, start - LookBack);
        int freeSkips = start - windowStart;
        int longest = candidates.Max(c => c.Count);
        // Never let an utterance reach further ahead than the number of words said, plus a couple of skips.
        int windowEnd = Math.Min(_parts.Count, start + longest + MaxSkipAhead);
        if (windowEnd <= windowStart) return null;

        var targets = _parts.Skip(windowStart).Take(windowEnd - windowStart).Select(p => p.Key).ToList();

        HeardMatch? best = null;
        foreach (var words in candidates)
        {
            var heard = words.Select(w => (Key: Tokenizer.Key(w.Text), w.Confidence)).Where(w => w.Key.Length > 0).ToList();
            var confidence = heard.Select(w => w.Confidence).ToList();
            var a = Aligner.Align(heard.Select(w => w.Key).ToList(), targets, freeSkips, confidence, profile);
            if (a != null && (best == null || a.Score > best.Value.Alignment.Score))
                best = new HeardMatch(a, windowStart, confidence);
        }
        return best;
    }

    /// <summary>
    /// Practice: reveal the words that were said. Hypotheses are re-aligned as they grow, so words
    /// appear while the user is still speaking; the final result also fills in skipped words.
    /// </summary>
    private void ApplyPractice(HeardMatch match, int start, bool final)
    {
        // Words with at least one matched part were spoken.
        var spokenViews = new HashSet<WordView>(match.Alignment.MatchedTargets.Select(t => _parts[match.PartIndex(t)].View));
        foreach (var view in spokenViews)
            if (view.RevealWord(RevealKind.Spoken)) _spoken++;

        int lastMatched = match.LastPart;

        // On the final result, reveal anything skipped between where the utterance started and the last
        // matched word. Words the recognizer tends to drop ("the", "of", or ones calibration found it
        // rarely hears in this voice) count as spoken; others as missed.
        if (final)
        {
            for (int i = start; i < lastMatched; i++)
            {
                var (view, key) = _parts[i];
                if (!view.IsConcealed || spokenViews.Contains(view)) continue;
                bool generous = Aligner.IsEasySkip(key, Profile);
                if (view.RevealWord(generous ? RevealKind.Spoken : RevealKind.Missed))
                {
                    if (generous) _spoken++; else _missed++;
                }
            }
        }

        _cursor = Math.Max(_cursor, lastMatched + 1);
        RevealUnspeakableBefore(_cursor);
    }

    // ---------- Calibration ----------

    private void Calibrate_Click(object sender, RoutedEventArgs e)
    {
        if (CalibrateButton.IsChecked == true) StartCalibration();
        else FinishCalibration(save: true);
    }

    private void StartCalibration()
    {
        StopSpeech();
        SetMode(PracticeMode.Full);
        _calibratedKeys.Clear();
        _cursor = 0;
        _utteranceStart = null;

        try
        {
            var passageWords = _tokens.Where(t => t.IsSpeakable).SelectMany(t => t.SpeechParts).ToList();
            _speech = new SpeechPractice(passageWords, 0, calibrate: true);
        }
        catch (Exception ex)
        {
            CalibrateButton.IsChecked = false;
            SpeechStatus.Text = ex.Message;
            return;
        }

        _calibrating = true;
        _speech.Hypothesized += words => DispatcherQueue.TryEnqueue(() => OnHeard(new[] { words }, final: false));
        _speech.Recognized += candidates => DispatcherQueue.TryEnqueue(() => OnHeard(candidates, final: true));
        _speech.AudioLevel += level => DispatcherQueue.TryEnqueue(() => MicLevel.Value = level);
        _speech.Start();

        CalibrateButton.IsChecked = true;
        CalibrateButtonText.Text = "Finish calibrating";
        FullButton.IsEnabled = FirstLetterButton.IsEnabled = BlurButton.IsEnabled = MicButton.IsEnabled = false;
        MicLevel.Visibility = Visibility.Visible;
        CompleteBar.IsOpen = false;
        SpeechStatus.Text = "Read the passage aloud at your normal reciting pace. Words turn green as they're heard.";
        UpdateProgress();
    }

    /// <summary>Calibration: mark words as read and record how well each one was heard.</summary>
    private void ApplyCalibration(HeardMatch match, int start, bool final)
    {
        var matchedParts = new HashSet<int>();
        foreach (var (spokenIdx, target) in match.Alignment.Matches)
        {
            int part = match.PartIndex(target);
            matchedParts.Add(part);
            _parts[part].View.SetReadMark(true);

            // Record final results only, and only words at/after where this utterance began
            // (look-back words were already recorded).
            if (final && part >= start && Profile != null)
            {
                Profile.RecordHeard(_parts[part].Key, match.Confidence[spokenIdx]);
                _calibratedKeys.Add(_parts[part].Key);
            }
        }

        if (final)
        {
            // Words between that the recognizer didn't pick up, even though the user was reading them.
            for (int i = start; i < match.LastPart; i++)
            {
                if (matchedParts.Contains(i)) continue;
                _parts[i].View.SetReadMark(true);
                if (Profile != null)
                {
                    Profile.RecordMissed(_parts[i].Key);
                    _calibratedKeys.Add(_parts[i].Key);
                }
            }
        }

        _cursor = Math.Max(_cursor, match.LastPart + 1);
    }

    private async void FinishCalibration(bool save)
    {
        if (!_calibrating) return;
        _calibrating = false;

        var speech = _speech;
        _speech = null;
        _utteranceStart = null;

        CalibrateButton.IsChecked = false;
        CalibrateButtonText.Text = "Calibrate voice";
        FullButton.IsEnabled = FirstLetterButton.IsEnabled = BlurButton.IsEnabled = true;
        MicButton.IsEnabled = _mode != PracticeMode.Full;
        MicLevel.Visibility = Visibility.Collapsed;
        foreach (var w in _words) w.SetCurrent(false);

        if (save && _calibratedKeys.Count > 0) ProfileChanged?.Invoke(this, EventArgs.Empty);

        if (_calibratedKeys.Count == 0)
        {
            SpeechStatus.Text = "Calibration stopped. Nothing was heard, so nothing was changed.";
            if (speech != null) await speech.ShutdownAsync(saveTraining: false);
            return;
        }

        var hard = _calibratedKeys
            .Where(k => Profile != null && Profile.Difficulty(k) >= 0.35)
            .OrderByDescending(k => Profile!.Difficulty(k))
            .Take(10)
            .Select(k => DisplayWord(k))
            .ToList();

        SpeechStatus.Text = "Saving calibration…";
        bool windowsSaved = speech != null && await speech.ShutdownAsync(saveTraining: save);

        CompleteBar.Title = "Calibration saved";
        CompleteBar.Message =
            $"Learned how {_calibratedKeys.Count} word{(_calibratedKeys.Count == 1 ? "" : "s")} sound in your voice. " +
            (hard.Count > 0 ? $"Extra leeway for: {string.Join(", ", hard)}. " : "Every word was heard clearly. ") +
            (windowsSaved ? "Your Windows voice profile was updated too." : "(Windows voice training wasn't available; the in-app calibration still applies.)");
        CompleteBar.IsOpen = true;
        SpeechStatus.Text = "Reading it once or twice more improves calibration further.";
        UpdateProgress();
    }

    private string DisplayWord(string key)
    {
        var part = _parts.FirstOrDefault(p => p.Key == key);
        if (part.View == null) return key;
        var word = new string(part.View.Token.Display.Where(c => char.IsLetter(c) || c is '\'' or '’' or '-').ToArray());
        return word.Length > 0 ? word : key;
    }

    /// <summary>Numerals and similar can't be recognized; reveal them once the reciter has passed them.</summary>
    private void RevealUnspeakableBefore(int cursor)
    {
        var limit = cursor < _parts.Count ? _words.IndexOf(_parts[cursor].View) : _words.Count;
        for (int i = 0; i < limit; i++)
            if (!_words[i].Token.IsSpeakable && _words[i].RevealWord(RevealKind.Missed))
                _missed++;
    }

    private void Back_Click(object sender, RoutedEventArgs e)
    {
        StopSpeech();
        BackRequested?.Invoke(this, EventArgs.Empty);
    }
}
