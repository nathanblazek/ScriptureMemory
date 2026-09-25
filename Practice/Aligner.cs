using System.Text;

namespace ScriptureMemory.Practice;

/// <summary>
/// Forgiving alignment of what the recognizer heard against the upcoming words of the passage.
/// Tolerates dropped words, extra words, and near-misses ("love" vs "loved", "to" vs "too").
/// </summary>
public static class Aligner
{
    private const double MatchThreshold = 0.62;

    // When the recognizer wasn't sure what it heard, lean toward the word we expected: the match
    // threshold drops by up to this much as confidence falls to zero (never below MinMatchThreshold).
    private const double LowConfidenceLeeway = 0.3;
    private const double MinMatchThreshold = 0.35;
    private const double ExtraSpokenCost = 0.5;
    // Higher than the gain from one match, so a lone match never justifies skipping two real words.
    private const double SkipWordCost = 1.2;
    private const double SkipFunctionWordCost = 0.25;

    /// <summary>Short words the recognizer often drops; skipping them is cheap and not counted as a miss.</summary>
    private static readonly HashSet<string> FunctionWords = new(StringComparer.Ordinal)
    {
        "a", "an", "the", "and", "of", "to", "in", "on", "for", "is", "it", "that", "he", "his", "i", "you",
        "be", "as", "by", "but", "or", "so", "not", "we", "my", "me", "was", "are", "at", "with", "from",
        "who", "this", "will", "shall", "have", "has", "had", "him", "her", "them", "they", "their", "your",
        "our", "us", "all", "if", "o", "into", "unto", "which", "do", "no", "nor", "then", "than", "there",
    };

    public static bool IsFunctionWord(string key) => FunctionWords.Contains(key);

    /// <summary>Words it's fine for the recognizer to drop: small function words, and words calibration found it rarely hears.</summary>
    public static bool IsEasySkip(string key, Models.VoiceProfile? profile) =>
        IsFunctionWord(key) || (profile?.IsHardToHear(key) ?? false);

    /// <param name="Matches">(spoken index, target index) for each matched word, in order.</param>
    public sealed record Alignment(double Score, IReadOnlyList<(int Spoken, int Target)> Matches)
    {
        public IEnumerable<int> MatchedTargets => Matches.Select(p => p.Target);
        public int LastMatchedTarget => Matches[^1].Target;
    }

    /// <summary>
    /// Aligns <paramref name="spoken"/> against <paramref name="targets"/>. The first
    /// <paramref name="freeSkips"/> targets are look-back words that may be skipped at no cost
    /// (the reciter may restart a few words back). <paramref name="confidence"/> (0..1 per spoken
    /// word, optional) loosens matching for words the recognizer was unsure about, and
    /// <paramref name="profile"/> loosens it for words calibration found hard to hear in this voice.
    /// Returns null if nothing matched.
    /// </summary>
    public static Alignment? Align(IReadOnlyList<string> spoken, IReadOnlyList<string> targets, int freeSkips,
                                   IReadOnlyList<float>? confidence = null, Models.VoiceProfile? profile = null)
    {
        int m = spoken.Count, n = targets.Count;
        if (m == 0 || n == 0) return null;

        var spokenLeeway = new double[m];
        for (int i = 0; i < m; i++)
        {
            double conf = confidence != null ? Math.Clamp(confidence[i], 0f, 1f) : 1.0;
            spokenLeeway[i] = LowConfidenceLeeway * (1 - conf);
        }
        var targetLeeway = new double[n];
        var skipCost = new double[n];
        for (int j = 0; j < n; j++)
        {
            targetLeeway[j] = profile?.ThresholdBonus(targets[j]) ?? 0;
            skipCost[j] = j < freeSkips ? 0 : IsEasySkip(targets[j], profile) ? SkipFunctionWordCost : SkipWordCost;
        }

        var dp = new double[m + 1, n + 1];
        var back = new byte[m + 1, n + 1]; // 1 = extra spoken, 2 = skipped target, 3 = match
        for (int i = 0; i <= m; i++)
            for (int j = 0; j <= n; j++)
                dp[i, j] = double.NegativeInfinity;
        dp[0, 0] = 0;

        for (int i = 0; i <= m; i++)
        {
            for (int j = 0; j <= n; j++)
            {
                var cur = dp[i, j];
                if (double.IsNegativeInfinity(cur)) continue;

                if (j < n)
                {
                    Relax(dp, back, i, j + 1, cur - skipCost[j], 2);
                }
                if (i < m)
                {
                    Relax(dp, back, i + 1, j, cur - ExtraSpokenCost, 1);
                }
                if (i < m && j < n)
                {
                    double sim = Similarity(spoken[i], targets[j]);
                    double threshold = Math.Max(MinMatchThreshold, MatchThreshold - spokenLeeway[i] - targetLeeway[j]);
                    if (sim >= threshold) Relax(dp, back, i + 1, j + 1, cur + 1 + sim, 3);
                }
            }
        }

        int bestJ = 0;
        for (int j = 1; j <= n; j++)
            if (dp[m, j] > dp[m, bestJ] + 1e-9) bestJ = j;

        var matched = new List<(int, int)>();
        int bi = m, bj = bestJ;
        while (bi > 0 || bj > 0)
        {
            switch (back[bi, bj])
            {
                case 3: matched.Add((bi - 1, bj - 1)); bi--; bj--; break;
                case 2: bj--; break;
                default: bi--; break;
            }
        }
        if (matched.Count == 0) return null;
        matched.Reverse();
        return new Alignment(dp[m, bestJ], matched);
    }

    private static void Relax(double[,] dp, byte[,] back, int i, int j, double value, byte move)
    {
        if (value > dp[i, j])
        {
            dp[i, j] = value;
            back[i, j] = move;
        }
    }

    /// <summary>0..1 similarity between two normalized words.</summary>
    public static double Similarity(string a, string b)
    {
        if (a == b) return 1;
        if (a.Length == 0 || b.Length == 0) return 0;

        int longer = Math.Max(a.Length, b.Length);
        double score = 1.0 - (double)Levenshtein(a, b) / longer;

        // Same stem: "believe" / "believes", "love" / "loved"
        var (s, l) = a.Length <= b.Length ? (a, b) : (b, a);
        if (s.Length >= 3 && l.StartsWith(s, StringComparison.Ordinal)) score = Math.Max(score, 0.8);

        // Sounds alike: "to" / "too" / "two", "their" / "there"
        if (a[0] == b[0] && Soundex(a) == Soundex(b) && Math.Abs(a.Length - b.Length) <= 2)
            score = Math.Max(score, 0.75);

        return score;
    }

    private static int Levenshtein(string a, string b)
    {
        var prev = new int[b.Length + 1];
        var cur = new int[b.Length + 1];
        for (int j = 0; j <= b.Length; j++) prev[j] = j;
        for (int i = 1; i <= a.Length; i++)
        {
            cur[0] = i;
            for (int j = 1; j <= b.Length; j++)
            {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                cur[j] = Math.Min(Math.Min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            (prev, cur) = (cur, prev);
        }
        return prev[b.Length];
    }

    private static string Soundex(string word)
    {
        static char Code(char c) => c switch
        {
            'b' or 'f' or 'p' or 'v' => '1',
            'c' or 'g' or 'j' or 'k' or 'q' or 's' or 'x' or 'z' => '2',
            'd' or 't' => '3',
            'l' => '4',
            'm' or 'n' => '5',
            'r' => '6',
            _ => '0',
        };

        var sb = new StringBuilder();
        sb.Append(word[0]);
        char last = Code(word[0]);
        for (int i = 1; i < word.Length && sb.Length < 4; i++)
        {
            char code = Code(word[i]);
            if (code != '0' && code != last) sb.Append(code);
            if (word[i] is not ('h' or 'w')) last = code;
        }
        return sb.ToString().PadRight(4, '0');
    }
}
