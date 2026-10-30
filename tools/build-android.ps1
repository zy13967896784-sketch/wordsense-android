# 构建 Android APK（不需要 Gradle）
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $root
$sdk = Join-Path $workspace '.android-sdk'
$bt = Join-Path $sdk 'build-tools\34.0.0'
$androidJar = Join-Path $sdk 'platforms\android-34\android.jar'
$aapt2 = Join-Path $bt 'aapt2.exe'
$zipalign = Join-Path $bt 'zipalign.exe'
$apksigner = Join-Path $bt 'apksigner.bat'
$d8 = Join-Path $bt 'd8.bat'

foreach ($tool in @($aapt2, $zipalign, $apksigner, $d8, $androidJar)) {
    if (-not (Test-Path $tool)) { throw "缺少 Android 工具：$tool" }
}

$mobile = Join-Path $root 'mobile'
$build = Join-Path $mobile 'build'
$dist = Join-Path $root 'dist'
$www = Join-Path $mobile 'assets\www'
$keystore = Join-Path $mobile 'wordsense.jks'
$outApk = Join-Path $dist 'WordSense-1.2.0-android.apk'

function Normalize-Apk([string]$srcPath, [string]$dstPath) {
    Write-Host "normalize $srcPath -> $dstPath"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $src = [System.IO.Compression.ZipFile]::OpenRead($srcPath)
    if (Test-Path $dstPath) { Remove-Item $dstPath -Force }
    $fs = [System.IO.File]::Create($dstPath)
    $dst = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
    foreach ($entry in $src.Entries) {
        $name = $entry.FullName.Replace('\', '/')
        $level = [System.IO.Compression.CompressionLevel]::Optimal
        if ($name -eq 'resources.arsc' -or $name.EndsWith('.so')) {
            $level = [System.IO.Compression.CompressionLevel]::NoCompression
        }
        $newEntry = $dst.CreateEntry($name, $level)
        $input = $entry.Open()
        $output = $newEntry.Open()
        $input.CopyTo($output)
        $output.Close()
        $input.Close()
    }
    $dst.Dispose()
    $fs.Close()
    $src.Dispose()
}

if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force $build | Out-Null
New-Item -ItemType Directory -Force $dist | Out-Null

# 1. 准备网页资源
if (Test-Path $www) { Remove-Item -Recurse -Force $www }
New-Item -ItemType Directory -Force $www | Out-Null
New-Item -ItemType Directory -Force (Join-Path $www 'data') | Out-Null
Copy-Item (Join-Path $root 'index.html') $www
Copy-Item (Join-Path $root 'manifest.webmanifest') $www
Copy-Item (Join-Path $root 'assets') $www -Recurse
Copy-Item (Join-Path $root 'js') $www -Recurse
Copy-Item (Join-Path $root 'data\words.js') (Join-Path $www 'data\words.js')
Remove-Item (Join-Path $www 'assets\app-icon-source.jpg') -Force -ErrorAction SilentlyContinue
Write-Host ("网页资源 {0:N2} MB" -f ((Get-ChildItem $www -Recurse -File | Measure-Object Length -Sum).Sum / 1MB))

# 2. 编译资源
& $aapt2 compile --dir (Join-Path $mobile 'res') -o (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

& $aapt2 link -o (Join-Path $build 'base.apk') `
    -I $androidJar `
    --manifest (Join-Path $mobile 'AndroidManifest.xml') `
    -R (Join-Path $build 'res.zip') `
    --auto-add-overlay `
    -A (Join-Path $mobile 'assets') `
    --java (Join-Path $build 'gen') `
    --min-sdk-version 21 `
    --target-sdk-version 33 `
    --version-code 12 `
    --version-name 1.2.0
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

# 3. 编译 Java
$sources = @()
$sources += Get-ChildItem (Join-Path $mobile 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem (Join-Path $build 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$rsp = Join-Path $build 'sources.txt'
$sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content -Path $rsp -Encoding ASCII
& javac --release 8 -parameters -encoding UTF-8 -classpath $androidJar -d (Join-Path $build 'classes') "@$rsp"
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

# 4. dex
New-Item -ItemType Directory -Force (Join-Path $build 'dex') | Out-Null
& jar --create --file (Join-Path $build 'classes.jar') -C (Join-Path $build 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'jar 打包 classes 失败' }
& $d8 --release --min-api 21 --lib $androidJar --output (Join-Path $build 'dex') (Join-Path $build 'classes.jar')
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

# 5. 把 classes.dex 放进 APK
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkPath = Join-Path $build 'base.apk'
$zip = [System.IO.Compression.ZipFile]::Open($apkPath, 'Update')
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$stream = $entry.Open()
$dexBytes = [System.IO.File]::ReadAllBytes((Join-Path $build 'dex\classes.dex'))
$stream.Write($dexBytes, 0, $dexBytes.Length)
$stream.Close()
$zip.Dispose()

# 5.5 统一路径，并把 resources.arsc 以 STORED 写回
# Android 11+ 强制 resources.arsc 未压缩 + 4 字节对齐，否则安装报 -124。
# .NET 的 ZipArchive 在 PowerShell 5.1 下写不出可靠的 STORED 条目，改用 Python。
$python = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $python) { throw '需要 Python 3 来重写 resources.arsc' }
& $python (Join-Path $root 'tools\repack-apk.py') $apkPath (Join-Path $build 'normalized.apk')
if ($LASTEXITCODE -ne 0) { throw 'repack apk 失败' }

# 6. 对齐 + 签名
& $zipalign -f -p 4 (Join-Path $build 'normalized.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -noprompt -keystore $keystore -alias wordsense `
        -storepass wordsense -keypass wordsense -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=WordSense, OU=Personal, O=WordSense, L=Chongqing, ST=Chongqing, C=CN"
    if ($LASTEXITCODE -ne 0) { throw '生成签名失败' }
}

& $apksigner sign --ks $keystore --ks-key-alias wordsense `
    --ks-pass pass:wordsense --key-pass pass:wordsense `
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --out $outApk (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner sign 失败' }

& $apksigner verify --verbose --print-certs $outApk
if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败' }

& $python (Join-Path $root 'tools\check-apk.py') $outApk
if ($LASTEXITCODE -ne 0) { throw 'resources.arsc 校验失败（安装会报 -124）' }

& $zipalign -c -v 4 $outApk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'zipalign 校验失败' }

Copy-Item $outApk (Join-Path $dist '词感-android.apk') -Force

$badging = & $aapt2 dump badging $outApk
$badging | Select-Object -First 6
Write-Host ("APK  {0:N2} MB  {1}" -f ((Get-Item $outApk).Length / 1MB), $outApk)
