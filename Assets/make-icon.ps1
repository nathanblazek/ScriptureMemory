# Generates Assets\app.ico (Bible with a gold cross) at the standard Windows icon sizes.
# Run with Windows PowerShell:  powershell -ExecutionPolicy Bypass -File Assets\make-icon.ps1
Add-Type -AssemblyName System.Drawing

function New-RoundedRect([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

function Draw-Bible([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.PixelOffsetMode = 'HighQuality'
    $g.Clear([System.Drawing.Color]::Transparent)
    $s = $size / 256.0
    $g.ScaleTransform($s, $s)
    $small = $size -le 32

    # Pages (cream block peeking out right and bottom)
    $pages = New-RoundedRect 58 30 170 208 12
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 238, 226, 196))), $pages)
    if (-not $small) {
        $linePen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 205, 188, 150)), 2
        foreach ($i in 0..4) { $g.DrawLine($linePen, 212 + $i * 3, 44, 212 + $i * 3, 222) }
        foreach ($i in 0..3) { $g.DrawLine($linePen, 74, 226 + $i * 3, 214, 226 + $i * 3) }
    }

    # Cover (deep burgundy leather)
    $cover = New-RoundedRect 28 18 180 210 16
    $coverBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.PointF 28, 18), (New-Object System.Drawing.PointF 208, 228), ([System.Drawing.Color]::FromArgb(255, 122, 30, 36)), ([System.Drawing.Color]::FromArgb(255, 64, 12, 18))
    $g.FillPath($coverBrush, $cover)

    # Spine
    $spine = New-RoundedRect 28 18 30 210 14
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 52, 8, 14))), $spine)

    $gold = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.PointF 0, 40), (New-Object System.Drawing.PointF 0, 200), ([System.Drawing.Color]::FromArgb(255, 250, 222, 130)), ([System.Drawing.Color]::FromArgb(255, 196, 146, 44))

    # Gold border inset on the cover (skipped at small sizes where it would just blur)
    if (-not $small) {
        $border = New-RoundedRect 70 34 124 178 8
        $g.DrawPath((New-Object System.Drawing.Pen $gold, 4), $border)
    }

    # Gold cross, thicker at small sizes so it stays readable
    $cx = 132
    $bar = if ($small) { 34 } else { 24 }
    $g.FillRectangle($gold, $cx - $bar / 2, 56, $bar, 136)
    $g.FillRectangle($gold, $cx - 42, 96 - $bar / 2 + 4, 84, $bar)

    $g.Dispose()
    return $bmp
}

$sizes = 16, 20, 24, 32, 40, 48, 64, 128, 256
$pngs = foreach ($sz in $sizes) {
    $bmp = Draw-Bible $sz
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    if ($sz -eq 256) { $bmp.Save((Join-Path $PSScriptRoot 'app-preview.png'), [System.Drawing.Imaging.ImageFormat]::Png) }
    $bmp.Dispose()
    , $ms.ToArray()
}

# ICO container with PNG-compressed entries
$out = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter $out
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $dim = if ($sizes[$i] -ge 256) { 0 } else { $sizes[$i] }
    $w.Write([byte]$dim); $w.Write([byte]$dim); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([uint16]1); $w.Write([uint16]32)
    $w.Write([uint32]$pngs[$i].Length); $w.Write([uint32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($png in $pngs) { $w.Write($png) }
$w.Flush()
[System.IO.File]::WriteAllBytes((Join-Path $PSScriptRoot 'app.ico'), $out.ToArray())
"Wrote app.ico ($($sizes -join ', ') px)"
