using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Foundation;

namespace ScriptureMemory.Practice;

/// <summary>Marker element that forces the <see cref="WrapPanel"/> to start a new line (paragraph break).</summary>
public sealed class ParagraphBreak : Grid
{
    public ParagraphBreak(double height) => Height = height;
}

/// <summary>Lays children out left-to-right, wrapping to a new line when the width runs out.</summary>
public sealed class WrapPanel : Panel
{
    public double HorizontalSpacing { get; set; } = 8;
    public double LineSpacing { get; set; } = 10;

    protected override Size MeasureOverride(Size availableSize)
    {
        return Layout(availableSize.Width, arrange: false);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        Layout(finalSize.Width, arrange: true);
        return finalSize;
    }

    private Size Layout(double maxWidth, bool arrange)
    {
        double x = 0, y = 0, lineHeight = 0, widest = 0;
        var line = new List<(UIElement Child, double X)>();

        void FinishLine()
        {
            if (arrange)
                foreach (var (child, cx) in line)
                    child.Arrange(new Rect(cx, y, child.DesiredSize.Width, child.DesiredSize.Height));
            line.Clear();
            y += lineHeight;
            x = 0;
            lineHeight = 0;
        }

        foreach (var child in Children)
        {
            if (!arrange) child.Measure(new Size(maxWidth, double.PositiveInfinity));
            var size = child.DesiredSize;

            if (child is ParagraphBreak)
            {
                if (line.Count > 0) FinishLine();
                if (arrange) child.Arrange(new Rect(0, y, 0, 0));
                y += size.Height;
                continue;
            }

            if (line.Count > 0 && x + size.Width > maxWidth)
            {
                FinishLine();
                y += LineSpacing;
            }

            line.Add((child, x));
            x += size.Width + HorizontalSpacing;
            lineHeight = Math.Max(lineHeight, size.Height);
            widest = Math.Max(widest, x - HorizontalSpacing);
        }
        if (line.Count > 0) FinishLine();

        return new Size(double.IsInfinity(maxWidth) ? widest : maxWidth, y);
    }
}
