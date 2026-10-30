#requires -Version 5.1
# Build and platform-sign the Flyme test APK on Windows.
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Checked {
    param([string]$FilePath, [string[]]$ArgumentList)
    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed (exit $LASTEXITCODE): $FilePath"
    }
}

function Assert-File {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing required input: $Path"
    }
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$androidSdk = $env:ANDROID_HOME
if (-not $androidSdk) { $androidSdk = $env:ANDROID_SDK_ROOT }
if (-not $androidSdk) {
    if (-not $env:LOCALAPPDATA) { throw 'Set ANDROID_HOME to your Android SDK directory.' }
    $androidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$env:ANDROID_HOME = $androidSdk
$signingDir = $env:DIPLAY_PLATFORM_SIGNING_DIR
if (-not $signingDir) { $signingDir = Join-Path $repoRoot 'AOSP-platform-signing' }
$buildTools = Join-Path $androidSdk 'build-tools\36.1.0'
$apkOutputDir = Join-Path $repoRoot 'build\car-test-apks'
# Keep this APK and its checksum together under one local-time stamp.
$buildStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$flymeApk = "Apple-CarPlay-flyme-test-$buildStamp.apk"
$privateKey = Join-Path $signingDir 'platform.pk8'
$certificate = Join-Path $signingDir 'platform.x509.pem'
$gradle = Join-Path $repoRoot 'gradlew.bat'
$apkSigner = Join-Path $buildTools 'apksigner.bat'
$zipAlign = Join-Path $buildTools 'zipalign.exe'
$aapt = Join-Path $buildTools 'aapt2.exe'

foreach ($path in @($privateKey, $certificate, $gradle, $apkSigner, $zipAlign, $aapt)) {
    Assert-File $path
}
if ($env:JAVA_HOME) {
    Assert-File (Join-Path $env:JAVA_HOME 'bin\java.exe')
} elseif (-not (Get-Command java.exe -ErrorAction SilentlyContinue)) {
    throw 'Install a JDK (JDK 25 is used by CI) and set JAVA_HOME or add java.exe to PATH.'
}
# Fail early with an actionable message for interrupted SDK installations.
$nativeBuild = Get-Content -LiteralPath (Join-Path $repoRoot 'shared\build.gradle') -Raw
$ndkMatch = [regex]::Match($nativeBuild, 'ndkVersion\s*=\s*[''"]([^''"]+)[''"]')
if ($ndkMatch.Success) {
    $ndkVersion = $ndkMatch.Groups[1].Value
    $ndkMetadata = Join-Path $androidSdk "ndk\$ndkVersion\source.properties"
    if (-not (Test-Path -LiteralPath $ndkMetadata -PathType Leaf)) {
        throw "Install the complete NDK $ndkVersion in Android Studio SDK Manager. Missing: $ndkMetadata"
    }
}

# Compute the certificate's DER SHA-256 using .NET; OpenSSL is not required.
$pem = Get-Content -LiteralPath $certificate -Raw
$pemMatch = [regex]::Match($pem, '(?s)-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----')
if (-not $pemMatch.Success) { throw 'Cannot read the PEM signing certificate.' }
$certificateDer = [Convert]::FromBase64String($pemMatch.Groups[1].Value)
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $expectedCertificate = ([BitConverter]::ToString($sha256.ComputeHash($certificateDer))).Replace('-', '').ToLowerInvariant()
} finally { $sha256.Dispose() }

$buildTask = ':mobile:assembleDebug'
if ($env:DIPLAY_AUTH_ASSETS_DIR -or (Test-Path -LiteralPath (Join-Path $repoRoot 'offline-mfi') -PathType Container)) {
    $buildTask = ':mobile:assembleStandaloneDebug'
    Write-Host 'Using local CarPlay authentication input (standalone build).'
} else {
    Write-Warning 'No root offline-mfi/ or DIPLAY_AUTH_ASSETS_DIR: source-only Flyme test APK.'
}

function Build-One {
    param([string]$Kind, [string]$PackageName, [string]$FinalName)
    Write-Host "`nBuilding $Kind"
    Invoke-Checked -FilePath $gradle -ArgumentList @($buildTask, '--console=plain')
    $unsignedApk = Join-Path $repoRoot 'mobile\build\outputs\apk\debug\mobile-debug.apk'
    Assert-File $unsignedApk
    if ((Get-Item -LiteralPath $unsignedApk).Length -eq 0) { throw 'Build output APK is empty.' }
    if ($buildTask -eq ':mobile:assembleStandaloneDebug') {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($unsignedApk)
        try {
            foreach ($entry in @('assets/offline-mfi/identity.pk8', 'assets/offline-mfi/certificate.p7b')) {
                $asset = $archive.GetEntry($entry)
                if ($null -eq $asset -or $asset.Length -eq 0) {
                    throw "Required authentication asset missing or empty in APK: $entry"
                }
            }
        } finally { $archive.Dispose() }
    }
    $alignedApk = Join-Path $apkOutputDir "$Kind-aligned.apk"
    $candidateApk = Join-Path $apkOutputDir "$Kind-candidate.apk"
    Invoke-Checked -FilePath $zipAlign -ArgumentList @('-f', '-P', '16', '4', $unsignedApk, $alignedApk)
    Invoke-Checked -FilePath $apkSigner -ArgumentList @('sign', '--key', $privateKey, '--cert', $certificate, '--out', $candidateApk, $alignedApk)
    Invoke-Checked -FilePath $zipAlign -ArgumentList @('-c', '-P', '16', '4', $candidateApk)
    $signature = @(Invoke-Checked -FilePath $apkSigner -ArgumentList @('verify', '--verbose', '--print-certs', $candidateApk))
    $signature | Set-Content -LiteralPath (Join-Path $apkOutputDir "$Kind-signature.txt") -Encoding UTF8
    $digestMatch = [regex]::Match(($signature -join "`n"), '(?m)^Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$')
    if (-not $digestMatch.Success -or $digestMatch.Groups[1].Value.ToLowerInvariant() -ne $expectedCertificate) {
        throw 'Signing certificate mismatch.'
    }
    $badging = @(Invoke-Checked -FilePath $aapt -ArgumentList @('dump', 'badging', $candidateApk))
    $manifest = @(Invoke-Checked -FilePath $aapt -ArgumentList @('dump', 'xmltree', $candidateApk, '--file', 'AndroidManifest.xml'))
    $badging | Set-Content -LiteralPath (Join-Path $apkOutputDir "$Kind-badging.txt") -Encoding UTF8
    $manifest | Set-Content -LiteralPath (Join-Path $apkOutputDir "$Kind-manifest.txt") -Encoding UTF8
    if (-not ($badging -join "`n").Contains("package: name='$PackageName'")) {
        throw "Unexpected APK package; expected $PackageName"
    }
    $manifestText = $manifest -join "`n"
    if (-not $manifestText.Contains('android.uid.system') -or -not $manifestText.Contains('com.shilapi.flymeeas.ENABLED')) {
        throw 'Flyme APK is missing the shared UID or EAS opt-in.'
    }
    $finalApk = Join-Path $apkOutputDir $FinalName
    Move-Item -LiteralPath $candidateApk -Destination $finalApk -Force
    foreach ($temporary in @($alignedApk, "$candidateApk.idsig")) {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
    Write-Host "Verified: $finalApk"
}

Push-Location -LiteralPath $repoRoot
try {
    New-Item -ItemType Directory -Path $apkOutputDir -Force | Out-Null
    Build-One -Kind flyme-test -PackageName com.shihab.diplay.flyme.test -FinalName $flymeApk
    $checksums = foreach ($name in @($flymeApk)) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $apkOutputDir $name) -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $name"
    }
    $checksums | Set-Content -LiteralPath (Join-Path $apkOutputDir "SHA256SUMS-$buildStamp.txt") -Encoding ASCII
    Write-Host "`nFlyme test APK verified. Output: $apkOutputDir"
    Write-Host 'Actual system UID and OEM grants must still be checked after installation on the car.'
} finally { Pop-Location }
