using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace ScriptureMemory.Models;

public class Passage
{
    public Guid Id { get; set; } = Guid.NewGuid();

    /// <summary>Canonical reference returned by the ESV API, e.g. "John 3:16–18".</summary>
    public string Reference { get; set; } = "";

    /// <summary>The query the user typed/selected.</summary>
    public string Query { get; set; } = "";

    /// <summary>Passage text with inline verse numbers like "[16]".</summary>
    public string Text { get; set; } = "";

    public DateTime AddedOn { get; set; } = DateTime.Now;
    public DateTime? LastPracticed { get; set; }
    public int PracticeCount { get; set; }
    public bool Mastered { get; set; }

    [JsonIgnore]
    public string Preview
    {
        get
        {
            var t = Regex.Replace(Text, @"\[\d+\]\s*", "");
            t = Regex.Replace(t, @"\s+", " ").Trim();
            return t.Length > 180 ? t[..180].TrimEnd() + "…" : t;
        }
    }

    [JsonIgnore]
    public string Status => LastPracticed is null
        ? "Not practiced yet"
        : $"Completed {PracticeCount} time{(PracticeCount == 1 ? "" : "s")} · last on {LastPracticed:MMM d, yyyy}";
}

public class VerseCollection
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = "";
    public List<Passage> Passages { get; set; } = new();

    public override string ToString() => Name;
}

public class AppData
{
    /// <summary>ESV API key, encrypted for the current Windows user (DPAPI), base64.</summary>
    public string? EsvApiKeyProtected { get; set; }

    public List<VerseCollection> Collections { get; set; } = new();

    public VoiceProfile VoiceProfile { get; set; } = new();
}

/// <summary>How the recognizer has heard one word when the user read it aloud during calibration.</summary>
public class WordStat
{
    /// <summary>Times the word was recognized (decayed so recent readings count more).</summary>
    public double Heard { get; set; }

    /// <summary>Sum of recognizer confidence over those recognitions.</summary>
    public double ConfidenceSum { get; set; }

    /// <summary>Times the word was read but the recognizer didn't pick it up.</summary>
    public double Missed { get; set; }
}

/// <summary>
/// Per-word calibration learned from the user reading passages aloud. Words the recognizer struggles
/// with in this user's voice get extra leeway during practice; words it hears clearly stay strict.
/// </summary>
public class VoiceProfile
{
    private const double MaxSamples = 12;       // older readings fade out after this many
    private const double MaxThresholdBonus = 0.25;

    public Dictionary<string, WordStat> Words { get; set; } = new();

    public void RecordHeard(string key, double confidence)
    {
        var s = Get(key);
        s.Heard++;
        s.ConfidenceSum += Math.Clamp(confidence, 0, 1);
        Decay(s);
    }

    public void RecordMissed(string key)
    {
        var s = Get(key);
        s.Missed++;
        Decay(s);
    }

    /// <summary>0..1: how hard this word is for the recognizer to hear in this user's voice.</summary>
    public double Difficulty(string key)
    {
        if (!Words.TryGetValue(key, out var s)) return 0;
        double samples = s.Heard + s.Missed;
        if (samples <= 0) return 0;
        double avgConfidence = s.Heard > 0 ? s.ConfidenceSum / s.Heard : 0;
        double missRate = s.Missed / samples;
        double difficulty = Math.Clamp((0.75 - avgConfidence) / 0.75, 0, 1) * 0.6 + missRate * 0.4;
        return difficulty * Math.Min(1, samples / 2); // trust it more after a couple of readings
    }

    /// <summary>How much to lower the match threshold for this word.</summary>
    public double ThresholdBonus(string key) => Difficulty(key) * MaxThresholdBonus;

    /// <summary>The recognizer usually fails to pick this word up at all, so don't penalize it when it's not heard.</summary>
    public bool IsHardToHear(string key) =>
        Words.TryGetValue(key, out var s) && s.Heard + s.Missed >= 2 && s.Missed / (s.Heard + s.Missed) >= 0.5;

    private WordStat Get(string key)
    {
        if (!Words.TryGetValue(key, out var s)) Words[key] = s = new WordStat();
        return s;
    }

    private static void Decay(WordStat s)
    {
        double total = s.Heard + s.Missed;
        if (total <= MaxSamples) return;
        double f = MaxSamples / total;
        s.Heard *= f;
        s.ConfidenceSum *= f;
        s.Missed *= f;
    }
}
