Add-Type -AssemblyName System.Drawing

$pAddress   = "C:\Users\Admin\.gemini\antigravity-ide\brain\1c0e11c2-f732-4762-9ae8-ee01ffad0b99\swing_phase_address_1791450388285.jpg"
$pBackswing = "C:\Users\Admin\.gemini\antigravity-ide\brain\1c0e11c2-f732-4762-9ae8-ee01ffad0b99\swing_phase_backswing_1791450420759.jpg"
$pImpact    = "C:\Users\Admin\.gemini\antigravity-ide\brain\1c0e11c2-f732-4762-9ae8-ee01ffad0b99\swing_phase_impact_1791450463723.jpg"
$pFinish    = "C:\Users\Admin\.gemini\antigravity-ide\brain\1c0e11c2-f732-4762-9ae8-ee01ffad0b99\golf_person_navy_1791435001468.jpg"

$assets = "d:\AceTrace\aceTrace_android\app\src\main\assets"
$sim = "d:\AceTrace\aceTrace_simulator\assets"
$drawable = "d:\AceTrace\aceTrace_android\app\src\main\res\drawable"

$items = @(
    @{ Src = $pAddress;   Name = "swing_1.jpg" },
    @{ Src = $pBackswing; Name = "swing_2.jpg" },
    @{ Src = $pImpact;    Name = "swing_3.jpg" },
    @{ Src = $pFinish;    Name = "swing_4.jpg" }
)

foreach ($item in $items) {
    $img = [System.Drawing.Image]::FromFile($item.Src)
    $bmp = New-Object System.Drawing.Bitmap 1080, 1920
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 0, 0, 1080, 1920)
    $g.Dispose()
    $img.Dispose()

    $p1 = Join-Path $assets $item.Name
    $bmp.Save($p1, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    
    $p2 = Join-Path $sim $item.Name
    $bmp.Save($p2, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    
    $p3 = Join-Path $drawable $item.Name
    $bmp.Save($p3, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    
    $bmp.Dispose()
    Write-Host "Processed $($item.Name)"
}
Write-Host "Swing animation frames ready!"
