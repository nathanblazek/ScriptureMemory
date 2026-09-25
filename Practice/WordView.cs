using Microsoft.UI;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;

namespace ScriptureMemory.Practice;

public enum PracticeMode { Full, FirstLetter, Blur }

public enum RevealKind
{
    Hidden,
    /// <summary>Revealed by saying it correctly.</summary>
    Spoken,
    /// <summary>Revealed by clicking it.</summary>
    Peeked,
    /// <summary>Skipped over while speaking (the next word was said instead).</summary>
    Missed,
}

/// <summary>One word of the passage. Shows the word, a first-letter hint, or a blurred version.</summary>
public sealed class WordView : Grid
{
    // Blur strength: radii (fraction of font size) of the rings of faint copies smeared around the word.
    private static readonly double[] BlurRings = { 0.15, 0.30, 0.45, 0.60 };
    private const int BlurCopiesPerRing = 8;
    private const double BlurCopyOpacity = 0.07;

    private static readonly (double X, double Y)[] BlurOffsets = BlurRings
        .SelectMany((r, ring) => Enumerable.Range(0, BlurCopiesPerRing).Select(k =>
        {
            // Stagger each ring's angles so copies don't line up into visible streaks.
            double angle = 2 * Math.PI * (k + ring * 0.5) / BlurCopiesPerRing;
            return (r * Math.Cos(angle), r * 0.7 * Math.Sin(angle));
        }))
        .ToArray();

    private readonly TextBlock _text;
    private readonly TextBlock _hint;
    private readonly Grid _blur;
    private readonly Rectangle _currentMarker;
    private PracticeMode _mode = PracticeMode.Full;

    public WordToken Token { get; }
    public RevealKind Reveal { get; private set; } = RevealKind.Hidden;

    /// <summary>True when the word is currently covered (hint or blur) and not yet revealed.</summary>
    public bool IsConcealed => _mode != PracticeMode.Full && Token.HasLetters && Reveal == RevealKind.Hidden;

    public WordView(WordToken token, double fontSize)
    {
        Token = token;
        Background = new SolidColorBrush(Colors.Transparent); // makes the whole box clickable

        _text = new TextBlock { Text = token.Display };
        _hint = new TextBlock { Text = token.Hint, Foreground = Brush("TextFillColorSecondaryBrush") };
        _blur = new Grid { IsHitTestVisible = false };
        foreach (var _ in BlurOffsets)
        {
            _blur.Children.Add(new TextBlock
            {
                Text = token.Display,
                Opacity = BlurCopyOpacity,
                RenderTransform = new TranslateTransform(),
            });
        }
        _currentMarker = new Rectangle
        {
            Height = 3,
            RadiusX = 1.5,
            RadiusY = 1.5,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(0, 0, 0, -5),
            Fill = Brush("AccentFillColorDefaultBrush"),
            Visibility = Visibility.Collapsed,
        };

        Children.Add(_text);
        Children.Add(_hint);
        Children.Add(_blur);
        Children.Add(_currentMarker);

        SetFontSize(fontSize);
        ApplyVisual();
    }

    public void SetFontSize(double size)
    {
        _text.FontSize = size;
        _hint.FontSize = size;
        for (int i = 0; i < _blur.Children.Count; i++)
        {
            var tb = (TextBlock)_blur.Children[i];
            tb.FontSize = size;
            var t = (TranslateTransform)tb.RenderTransform;
            t.X = BlurOffsets[i].X * size;
            t.Y = BlurOffsets[i].Y * size;
        }
    }

    public void SetMode(PracticeMode mode)
    {
        _mode = mode;
        Reveal = RevealKind.Hidden;
        _readMark = false;
        ApplyVisual();
    }

    private bool _readMark;

    /// <summary>Calibration: marks a visible word as read aloud (shown in green).</summary>
    public void SetReadMark(bool read)
    {
        _readMark = read;
        ApplyVisual();
    }

    /// <summary>Reveals the word; returns false if it was already showing.</summary>
    public bool RevealWord(RevealKind kind)
    {
        if (!IsConcealed) return false;
        Reveal = kind;
        ApplyVisual();
        return true;
    }

    public void SetCurrent(bool isCurrent)
    {
        _currentMarker.Visibility = isCurrent ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ApplyVisual()
    {
        bool concealed = IsConcealed;
        _text.Visibility = concealed ? Visibility.Collapsed : Visibility.Visible;
        _hint.Visibility = concealed && _mode == PracticeMode.FirstLetter ? Visibility.Visible : Visibility.Collapsed;
        _blur.Visibility = concealed && _mode == PracticeMode.Blur ? Visibility.Visible : Visibility.Collapsed;

        _text.Foreground = Reveal switch
        {
            RevealKind.Spoken => Brush("SystemFillColorSuccessBrush"),
            RevealKind.Peeked => Brush("AccentTextFillColorPrimaryBrush"),
            RevealKind.Missed => Brush("SystemFillColorCautionBrush"),
            _ when _readMark => Brush("SystemFillColorSuccessBrush"),
            _ => Brush("TextFillColorPrimaryBrush"),
        };

        ProtectedCursor = InputSystemCursor.Create(concealed ? InputSystemCursorShape.Hand : InputSystemCursorShape.Arrow);
    }

    private static Brush Brush(string key) => (Brush)Application.Current.Resources[key];
}
