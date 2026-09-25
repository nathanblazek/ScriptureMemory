using System.Speech.Recognition;

namespace ScriptureMemory.Practice;

/// <summary>A recognized word with the recognizer's confidence (0..1).</summary>
public readonly record struct HeardWord(string Text, float Confidence);

/// <summary>
/// Wraps the offline Windows speech recognizer, steering it with what the reciter is expected to say.
///
/// Two grammars compete for every utterance:
///  • "expected" (high weight): the next words of the passage from the reciter's position, in order,
///    stopping anywhere, with small function words optional. Rebuilt as the reciter advances.
///  • "passage" (lower weight): any of the passage's words and 2–3 word phrases, in any order. This
///    catches wrong or out-of-order words so they aren't forced into the expected phrase.
///
/// Events are raised on a background thread.
/// </summary>
public sealed class SpeechPractice : IDisposable
{
    private const int ExpectedAhead = 30;   // words of upcoming text in the expected grammar
    private const int ExpectedLookBack = 3; // the expected phrase may also start this many words back

    private readonly SpeechRecognitionEngine _engine;
    private readonly IReadOnlyList<string> _passageWords;
    private readonly System.Globalization.CultureInfo _culture;
    private readonly ManualResetEventSlim _stopped = new(true);
    private Grammar? _expected;
    private int _expectedPosition = -1;
    private int _pendingPosition;
    private bool _running;
    private bool _windowsTraining;

    /// <summary>True if this session is feeding the Windows speech profile (calibration mode).</summary>
    public bool IsTrainingWindows => _windowsTraining;

    /// <summary>Partial words while the user is still speaking.</summary>
    public event Action<IReadOnlyList<HeardWord>>? Hypothesized;

    /// <summary>End of an utterance: candidate word lists, best first (may be low confidence).</summary>
    public event Action<IReadOnlyList<IReadOnlyList<HeardWord>>>? Recognized;

    /// <summary>Microphone input level, 0–100.</summary>
    public event Action<int>? AudioLevel;

    /// <param name="passageWords">The passage's speakable words, in reading order.</param>
    /// <param name="startPosition">Index in <paramref name="passageWords"/> where the reciter begins.</param>
    /// <param name="calibrate">
    /// Calibration: the user is reading the visible text. Only the passage itself is listened for, and
    /// the Windows recognizer is put in training mode so it adapts its voice profile to this user.
    /// </param>
    public SpeechPractice(IReadOnlyList<string> passageWords, int startPosition, bool calibrate = false)
    {
        if (passageWords.Count == 0) throw new InvalidOperationException("This passage has no words that can be spoken.");
        _passageWords = passageWords;

        var info = SpeechRecognitionEngine.InstalledRecognizers()
                       .FirstOrDefault(r => r.Culture.Name.Equals("en-US", StringComparison.OrdinalIgnoreCase))
                   ?? SpeechRecognitionEngine.InstalledRecognizers()
                       .FirstOrDefault(r => r.Culture.TwoLetterISOLanguageName == "en")
                   ?? throw new InvalidOperationException(
                       "No English speech recognizer is installed. Add English speech in Windows Settings > Time & language > Speech.");
        _culture = info.Culture;
        _engine = new SpeechRecognitionEngine(info);

        // When calibrating, the user reads the exact text, so there's no need for the catch-all grammar,
        // and leaving it out keeps wrong words from ever being used to train the voice profile.
        if (!calibrate) _engine.LoadGrammar(BuildPassageGrammar());
        LoadExpected(startPosition);

        try
        {
            _engine.EndSilenceTimeout = TimeSpan.FromMilliseconds(500);
            _engine.EndSilenceTimeoutAmbiguous = TimeSpan.FromMilliseconds(700);
            _engine.MaxAlternates = 8;
        }
        catch (ArgumentOutOfRangeException) { /* keep recognizer defaults */ }

        // Reject as little as possible; the expected-text comparison decides what counts.
        try { _engine.UpdateRecognizerSetting("CFGConfidenceRejectionThreshold", 5); } catch { }

        try
        {
            _engine.SetInputToDefaultAudioDevice();
        }
        catch (InvalidOperationException)
        {
            _engine.Dispose();
            throw new InvalidOperationException(
                "No microphone found. Connect a microphone and make sure desktop apps are allowed to use it (Settings > Privacy & security > Microphone).");
        }

        _engine.SpeechHypothesized += (_, e) => Hypothesized?.Invoke(Words(e.Result));
        _engine.SpeechRecognized += (_, e) => RaiseRecognized(e.Result);
        _engine.SpeechRecognitionRejected += (_, e) => RaiseRecognized(e.Result);
        _engine.AudioLevelUpdated += (_, e) => AudioLevel?.Invoke(e.AudioLevel);
        _engine.RecognizerUpdateReached += (_, _) => LoadExpected(_pendingPosition);
        _engine.RecognizeCompleted += (_, _) => _stopped.Set();

        if (calibrate) _windowsTraining = WindowsTraining.TrySetTrainingState(_engine, training: true, adapt: true);
    }

    /// <summary>Tells the recognizer where the reciter is now, so it expects the words that follow.</summary>
    public void SetExpectedPosition(int position)
    {
        if (position == _expectedPosition) return;
        _pendingPosition = position;
        if (_running) _engine.RequestRecognizerUpdate(); // grammar swap happens in RecognizerUpdateReached
        else LoadExpected(position);
    }

    private void LoadExpected(int position)
    {
        if (position == _expectedPosition) return;
        if (_expected != null) _engine.UnloadGrammar(_expected);
        _expected = null;
        _expectedPosition = position;

        if (position >= _passageWords.Count) return; // reciter is at the end of the passage

        // [w(p-3)] [w(p-2)] [w(p-1)] w(p) [w(p+1) [w(p+2) …]]
        // A few words before the position are optional so the reciter can back up and restart.
        // (One chain rather than a Choices of chains: System.Speech fails on identical shared sub-builders.)
        var root = new GrammarBuilder { Culture = _culture };
        for (int s = Math.Max(0, position - ExpectedLookBack); s < position; s++)
            root.Append(new GrammarBuilder(_passageWords[s]) { Culture = _culture }, 0, 1);
        root.Append(BuildChain(position, Math.Min(_passageWords.Count, position + ExpectedAhead), first: true));

        _expected = new Grammar(root) { Name = "expected", Weight = 1.0f, Priority = 1 };
        _engine.LoadGrammar(_expected);
    }

    /// <summary>words[i] [words[i+1] [words[i+2] …]] — i.e. the upcoming text, stopping anywhere.</summary>
    private GrammarBuilder BuildChain(int i, int end, bool first)
    {
        var gb = new GrammarBuilder { Culture = _culture };
        var word = _passageWords[i];
        if (!first && Aligner.IsFunctionWord(Tokenizer.Key(word)))
            gb.Append(new GrammarBuilder(word) { Culture = _culture }, 0, 1); // "the", "of", … may be dropped
        else
            gb.Append(word);

        if (i + 1 < end)
            gb.Append(BuildChain(i + 1, end, first: false), 0, 1);
        return gb;
    }

    private Grammar BuildPassageGrammar()
    {
        var phrases = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        for (int i = 0; i < _passageWords.Count; i++)
        {
            phrases.Add(_passageWords[i]);
            if (i + 1 < _passageWords.Count) phrases.Add(_passageWords[i] + " " + _passageWords[i + 1]);
            if (i + 2 < _passageWords.Count) phrases.Add(_passageWords[i] + " " + _passageWords[i + 1] + " " + _passageWords[i + 2]);
        }
        var anyPhrase = new GrammarBuilder(new Choices(phrases.ToArray())) { Culture = _culture };
        var utterance = new GrammarBuilder(anyPhrase, 1, 60) { Culture = _culture };
        return new Grammar(utterance) { Name = "passage", Weight = 0.5f, Priority = 0 };
    }

    private void RaiseRecognized(RecognitionResult? result)
    {
        var candidates = new List<IReadOnlyList<HeardWord>>();
        if (result != null)
        {
            candidates.Add(Words(result));
            foreach (var alt in result.Alternates) candidates.Add(Words(alt));
        }
        Recognized?.Invoke(candidates.Where(c => c.Count > 0).ToList());
    }

    private static IReadOnlyList<HeardWord> Words(RecognizedPhrase? phrase) =>
        phrase?.Words.Select(w => new HeardWord(w.Text, w.Confidence)).ToList() ?? new List<HeardWord>();

    public void Start()
    {
        if (_running) return;
        _stopped.Reset();
        _engine.RecognizeAsync(RecognizeMode.Multiple);
        _running = true;
    }

    /// <summary>
    /// Stops listening and releases the recognizer on a background thread (so the UI never waits on it).
    /// In calibration mode, <paramref name="saveTraining"/> tells Windows to adapt its voice profile from
    /// what was read (true) or discard it (false). The returned task reports whether training was saved.
    /// </summary>
    public Task<bool> ShutdownAsync(bool saveTraining) => Task.Run(() =>
    {
        bool saved = false;
        try
        {
            if (_running)
            {
                _engine.RecognizeAsyncCancel();
                _stopped.Wait(TimeSpan.FromSeconds(3)); // training state can only change once recognition has stopped
                _running = false;
            }
            if (_windowsTraining)
            {
                saved = WindowsTraining.TrySetTrainingState(_engine, training: false, adapt: saveTraining) && saveTraining;
                _windowsTraining = false;
            }
        }
        catch { }
        finally
        {
            _engine.Dispose();
        }
        return saved;
    });

    public void Dispose() => _ = ShutdownAsync(saveTraining: false);
}
