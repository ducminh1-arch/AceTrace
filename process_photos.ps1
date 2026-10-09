Add-Type -AssemblyName System.Drawing

$src1 = "C:\Users\Admin\.gemini\antigravity-ide\brain\1c0e11c2-f732-4762-9ae8-ee01ffad0b99\golf_person_navy_1791435001468.jpg"
$src2 = "d:\AceTrace\aceTrace_android\app\src\main\assets\golf_vertical.jpg"
$src3 = "C:\Users\Admin\.gemini\antigravity-ide\brain\1c0e11c2-f732-4762-9ae8-ee01ffad0b99\golf_person_male_1791435041529.jpg"

$assets = "d:\AceTrace\aceTrace_android\app\src\main\assets"
$sim = "d:\AceTrace\aceTrace_simulator\assets"
$drawable = "d:\AceTrace\aceTrace_android\app\src\main\res\drawable"

New-Item -ItemType Directory -Force -Path $assets | Out-Null
New-Item -ItemType Directory -Force -Path $sim | Out-Null
New-Item -ItemType Directory -Force -Path $drawable | Out-Null

$items = @(
    @{ Src = $src1; Name = "golf_1.jpg"; Alt = "golf_navy.jpg" },
    @{ Src = $src2; Name = "golf_2.jpg"; Alt = "golf_vertical.jpg" },
    @{ Src = $src3; Name = "golf_3.jpg"; Alt = "golf_male.jpg" }
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
    
    $p2 = Join-Path $assets $item.Alt
    $bmp.Save($p2, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    
    $p3 = Join-Path $sim $item.Name
    $bmp.Save($p3, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    
    $p4 = Join-Path $drawable $item.Name
    $bmp.Save($p4, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    
    $bmp.Dispose()
    Write-Host "Saved $($item.Name)"
}
Write-Host "Completed all 3 photos!"
