using System.Text;
using System.Text.RegularExpressions;

namespace ScriptureMemory.Practice;

public enum TokenKind { Word, VerseNumber, ParagraphBreak }

public sealed class WordToken
{
    public TokenKind Kind { get; init; }

    /// <summary>Text exactly as shown, including punctuation (e.g. "“For", "world,").</summary>
    public string Display { get; init; } = "";

    /// <summary>First-letter hint, e.g. "Jesus" → "J____", "world," → "w____,".</summary>
    public string Hint { get; init; } = "";

    /// <summary>True if the token has letters/digits and so can be hidden.</summary>
    public bool HasLetters { get; init; }

    /// <summary>Words to put in the speech grammar ("well-pleased" → ["well", "pleased"]).</summary>
    public string[] SpeechParts { get; init; } = [];

    /// <summary>Comparison keys for <see cref="SpeechParts"/> (lowercase, letters only).</summary>
    public string[] MatchParts { get; init; } = [];

    public bool IsSpeakable => Kind == TokenKind.Word && MatchParts.Length > 0;
}

public static class Tokenizer
{
    private static readonly Regex LeadingVerseNumber = new(@"^\[(\d+)\](.*)$");

    public static List<WordToken> Tokenize(string text)
    {
        var tokens = new List<WordToken>();
        var paragraphs = Regex.Split(text.Replace("\r", ""), @"\n\s*\n");

        foreach (var paragraph in paragraphs)
        {
            if (string.IsNullOrWhiteSpace(paragraph)) continue;
            if (tokens.Count > 0) tokens.Add(new WordToken { Kind = TokenKind.ParagraphBreak });

            foreach (var raw in Regex.Split(paragraph.Trim(), @"\s+"))
            {
                var rest = raw;
                var m = LeadingVerseNumber.Match(rest);
                if (m.Success)
                {
                    tokens.Add(new WordToken { Kind = TokenKind.VerseNumber, Display = m.Groups[1].Value });
                    rest = m.Groups[2].Value;
                }
                if (rest.Length == 0) continue;

                // ESV joins em-dash clauses without spaces ("said—and"); split them into separate words.
                foreach (Match piece in Regex.Matches(rest, "[^—]+—*|—+"))
                    tokens.Add(MakeWord(piece.Value));
            }
        }
        return tokens;
    }

    public static string Key(string s)
    {
        var sb = new StringBuilder(s.Length);
        foreach (var ch in s)
            if (char.IsLetterOrDigit(ch)) sb.Append(char.ToLowerInvariant(ch));
        return sb.ToString();
    }

    private static WordToken MakeWord(string display)
    {
        var parts = new List<string>();
        foreach (var part in display.Split('-', '‐', '–'))
        {
            var sb = new StringBuilder();
            foreach (var ch in part)
            {
                if (char.IsLetterOrDigit(ch)) sb.Append(char.ToLowerInvariant(ch));
                else if (ch is '\'' or '’') sb.Append('\'');
            }
            var core = sb.ToString().Trim('\'');
            if (core.Length > 0) parts.Add(core);
        }

        // Digits (e.g. "144,000") can't go in a speech grammar reliably; those words are click-to-reveal only.
        bool speakable = parts.Count > 0 && parts.All(p => p.All(ch => char.IsLetter(ch) || ch == '\''));

        return new WordToken
        {
            Kind = TokenKind.Word,
            Display = display,
            Hint = MakeHint(display),
            HasLetters = parts.Count > 0,
            SpeechParts = speakable ? parts.ToArray() : [],
            MatchParts = speakable ? parts.Select(Key).ToArray() : [],
        };
    }

    private static string MakeHint(string display)
    {
        var sb = new StringBuilder(display.Length);
        bool first = true;
        foreach (var ch in display)
        {
            if (char.IsLetterOrDigit(ch))
            {
                sb.Append(first ? ch : '_');
                first = false;
            }
            else
            {
                sb.Append(ch);
            }
        }
        return sb.ToString();
    }
}
