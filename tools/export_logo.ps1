$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$source = Join-Path $project 'assets\logo-master.png'
$outputDirectory = Join-Path $project 'app\res\drawable-nodpi'
$output = Join-Path $outputDirectory 'ic_launcher.png'

Add-Type -AssemblyName System.Drawing
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$inputImage = [System.Drawing.Image]::FromFile($source)
$icon = New-Object System.Drawing.Bitmap(256, 256, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($icon)
try {
    $graphics.Clear([System.Drawing.Color]::Transparent)
    $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.DrawImage($inputImage, 0, 0, 256, 256)
    $icon.Save($output, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $graphics.Dispose()
    $icon.Dispose()
    $inputImage.Dispose()
}
Write-Host "Exported $output"
