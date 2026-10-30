#requires -Version 5.1
# Build four test APKs (normal, normal-r8, system, system-r8) and platform-sign each on Windows.
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
$versionName = '0.2.26'
$buildGradle = Get-Content -LiteralPath (Join-Path $repoRoot 'mobile\build.gradle.kts') -Raw
$versionMatch = [regex]::Match($buildGradle, 'versionName\s*=\s*"([^"]+)"')
if ($versionMatch.Success) { $versionName = $versionMatch.Groups[1].Value }
$versionTag = "v$versionName"
$normalApk = "Apple-CarPlay-$versionTag-normal.apk"
$normalR8Apk = "Apple-CarPlay-$versionTag-normal-r8.apk"
$systemApk = "Apple-CarPlay-$versionTag-system.apk"
$systemR8Apk = "Apple-CarPlay-$versionTag-system-r8.apk"

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

# Compute the certificate's DER SHA-256 using .NET; OpenSSL is not required.
$pem = Get-Content -LiteralPath $certificate -Raw
$pemMatch = [regex]::Match($pem, '(?s)-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----')
if (-not $pemMatch.Success) { throw 'Cannot read the PEM signing certificate.' }
$certificateDer = [Convert]::FromBase64String($pemMatch.Groups[1].Value)
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $expectedCertificate = ([BitConverter]::ToString($sha256.ComputeHash($certificateDer))).Replace('-', '').ToLowerInvariant()
} finally { $sha256.Dispose() }

$taskDebug = ':mobile:assembleDebug'
$taskOptimized = ':mobile:assembleOptimizedDebug'
$standalone = $false
if ($env:DIPLAY_AUTH_ASSETS_DIR -or (Test-Path -LiteralPath (Join-Path $repoRoot 'offline-mfi') -PathType Container)) {
    $taskDebug = ':mobile:assembleStandaloneDebug'
    $taskOptimized = ':mobile:assembleStandaloneOptimizedDebug'
    $standalone = $true
    Write-Host 'Using local CarPlay authentication input (standalone build).'
} else {
    Write-Warning 'No root offline-mfi/ or DIPLAY_AUTH_ASSETS_DIR: source-only test APKs.'
}

function Build-One {
    param(
        [string]$Kind,
        [bool]$System,
        [bool]$R8,
        [string]$PackageName,
        [string]$FinalName
    )
    $task = if ($R8) { $taskOptimized } else { $taskDebug }
    $relativeApk = if ($R8) { 'mobile\build\outputs\apk\optimizedDebug\mobile-optimizedDebug.apk' } else { 'mobile\build\outputs\apk\debug\mobile-debug.apk' }
    $unsignedApk = Join-Path $repoRoot $relativeApk

    Write-Host "`nBuilding $Kind (flymeSystem=$System, r8=$R8)"
    if (Test-Path -LiteralPath $unsignedApk) { Remove-Item -LiteralPath $unsignedApk -Force }
    $systemProp = "-PflymeSystem=$($System.ToString().ToLower())"
    Invoke-Checked -FilePath $gradle -ArgumentList @($systemProp, $task, '--console=plain', '--no-configuration-cache')
    Assert-File $unsignedApk
    if ((Get-Item -LiteralPath $unsignedApk).Length -eq 0) { throw 'Build output APK is empty.' }

    if ($standalone) {
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
    if ($System) {
        if (-not $manifestText.Contains('android.uid.system') -or -not $manifestText.Contains('com.shilapi.flymeeas.ENABLED')) {
            throw 'System APK is missing the shared UID or EAS opt-in.'
        }
    } else {
        if ($manifestText.Contains('android.uid.system') -or $manifestText.Contains('com.shilapi.flymeeas.ENABLED')) {
            throw 'Ordinary APK unexpectedly contains shared UID or EAS opt-in.'
        }
    }
    $finalApk = Join-Path $apkOutputDir $FinalName
    Move-Item -LiteralPath $candidateApk -Destination $finalApk -Force
    foreach ($temporary in @($alignedApk, "$candidateApk.idsig")) {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
    if ($R8) {
        $mappingSrc = Join-Path $repoRoot 'mobile\build\outputs\mapping\optimizedDebug\mapping.txt'
        if (Test-Path -LiteralPath $mappingSrc) {
            $mappingName = [System.IO.Path]::GetFileNameWithoutExtension($FinalName) + "-mapping.txt"
            $mappingFinal = Join-Path $apkOutputDir $mappingName
            Copy-Item -LiteralPath $mappingSrc -Destination $mappingFinal -Force
            Write-Host "Archived R8 mapping: $mappingFinal"
        }
    }
    Write-Host "Verified: $finalApk"
}

Push-Location -LiteralPath $repoRoot
try {
    New-Item -ItemType Directory -Path $apkOutputDir -Force | Out-Null
    Build-One -Kind normal -System $false -R8 $false -PackageName com.shihab.diplay.test -FinalName $normalApk
    Build-One -Kind normal-r8 -System $false -R8 $true -PackageName com.shihab.diplay.test -FinalName $normalR8Apk
    Build-One -Kind system -System $true -R8 $false -PackageName com.shihab.diplay.flyme.test -FinalName $systemApk
    Build-One -Kind system-r8 -System $true -R8 $true -PackageName com.shihab.diplay.flyme.test -FinalName $systemR8Apk

    $checksums = foreach ($name in @($normalApk, $normalR8Apk, $systemApk, $systemR8Apk)) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $apkOutputDir $name) -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $name"
    }
    $checksums | Set-Content -LiteralPath (Join-Path $apkOutputDir "SHA256SUMS-$versionTag.txt") -Encoding ASCII
    Write-Host "`nAll four test APKs verified. Output: $apkOutputDir"
} finally { Pop-Location }
