$xcassets = "d:\AceTrace\aceTrace_ios\Assets.xcassets"
New-Item -ItemType Directory -Force -Path $xcassets | Out-Null

# Contents.json for root
@'
{
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
'@ | Set-Content (Join-Path $xcassets "Contents.json") -Encoding UTF8

# AppIcon
$appIconDir = Join-Path $xcassets "AppIcon.appiconset"
New-Item -ItemType Directory -Force -Path $appIconDir | Out-Null
@'
{
  "images" : [
    {
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
'@ | Set-Content (Join-Path $appIconDir "Contents.json") -Encoding UTF8

# Function to create an image set
function Add-ImageSet($name, $srcFile, $isPng = $false) {
    $dir = Join-Path $xcassets "$name.imageset"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $ext = if ($isPng) { "png" } else { "jpg" }
    $dest = Join-Path $dir "$name.$ext"
    Copy-Item $srcFile $dest -Force

    $json = @"
{
  "images" : [
    {
      "filename" : "$name.$ext",
      "idiom" : "universal",
      "scale" : "1x"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
"@
    $json | Set-Content (Join-Path $dir "Contents.json") -Encoding UTF8
    Write-Host "Created $name.imageset"
}

$androidAssets = "d:\AceTrace\aceTrace_android\app\src\main\assets"

Add-ImageSet "logo" (Join-Path $androidAssets "logo.png") $true
Add-ImageSet "swing_1" (Join-Path $androidAssets "swing_1.jpg") $false
Add-ImageSet "swing_2" (Join-Path $androidAssets "swing_2.jpg") $false
Add-ImageSet "swing_3" (Join-Path $androidAssets "swing_3.jpg") $false
Add-ImageSet "swing_4" (Join-Path $androidAssets "swing_4.jpg") $false
Add-ImageSet "golf_1" (Join-Path $androidAssets "golf_1.jpg") $false
Add-ImageSet "golf_2" (Join-Path $androidAssets "golf_2.jpg") $false
Add-ImageSet "golf_3" (Join-Path $androidAssets "golf_3.jpg") $false

Write-Host "Assets.xcassets created successfully!"
