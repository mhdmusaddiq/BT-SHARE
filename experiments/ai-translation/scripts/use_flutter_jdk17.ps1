# Fix Android JVM compatibility for audio_stream_player 1.1.0.
# The package targets Java 17, but some Flutter JDK 21 installations
# compile Kotlin with JVM target 21. Use a verified installed JDK 17.
# No files in Pub Cache or Android generated Gradle projects are modified.
# NOTE: flutter config --jdk-dir affects all local Flutter projects.
[CmdletBinding()]
param(
    [string]$JdkPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Test-InstalledJdk17([string]$Directory) {
    if ([string]::IsNullOrWhiteSpace($Directory)) { return $false }
    $java = Join-Path $Directory "bin\java.exe"
    $javac = Join-Path $Directory "bin\javac.exe"
    if (-not ((Test-Path -LiteralPath $java) -and (Test-Path -LiteralPath $javac))) { return $false }
    # Java writes -version to stderr. Avoid NativeCommandError in PS 5.
    $start = [System.Diagnostics.ProcessStartInfo]::new($java)
    $start.Arguments = "-version"
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($start)
    try {
        $lines = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { return $false }
    }
    finally {
        $process.Dispose()
    }
    return [bool]($lines -match '(?m)(?:openjdk|java) version "17(?:\\.|")|(?:openjdk|java) 17(?:\\.|\\s)')
}

$candidates = [System.Collections.Generic.List[string]]::new()
if ($JdkPath) { $candidates.Add($JdkPath) }
else {
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    foreach ($root in @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Java",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\Zulu",
        "C:\Program Files\BellSoft",
        "C:\Program Files\Android"
    )) {
        if (Test-Path -LiteralPath $root) {
            Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
                ForEach-Object {
                    if ($_.Name -match '17|jdk|jbr') {
                        $candidates.Add($_.FullName)
                        # Android Studio typically uses an embedded JBR directory.
                        $jbr = Join-Path $_.FullName "jbr"
                        if (Test-Path -LiteralPath $jbr) {
                            $candidates.Add($jbr)
                        }
                    }
                }
        }
    }
}

$selected = $null
foreach ($candidate in $candidates) {
    if (Test-InstalledJdk17 $candidate) {
        $selected = (Resolve-Path -LiteralPath $candidate).Path
        break
    }
}
if (-not $selected) {
    Write-Host "JDK 17 was not found. No Flutter settings changed." -ForegroundColor Yellow
    Write-Host "Install Eclipse Temurin JDK 17, then run this script again."
    Write-Host "Windows: winget install -e --id EclipseAdoptium.Temurin.17.JDK"
    Write-Host "Or run: powershell -File .\scripts\use_flutter_jdk17.ps1 -JdkPath 'C:\full\path\to\jdk-17'"
    exit 2
}

Write-Host "Validated JDK 17 at: $selected" -ForegroundColor Green
Write-Host "IMPORTANT: flutter config --jdk-dir is global across Flutter projects." -ForegroundColor Yellow
& flutter config "--jdk-dir=$selected"
if ($LASTEXITCODE -ne 0) { throw "Flutter could not switch to the selected JDK." }

Write-Host "Flutter JDK configured. Verify 'flutter doctor -v' shows Java 17." -ForegroundColor Green
& flutter doctor -v
if ($LASTEXITCODE -ne 0) {
    Write-Warning "flutter doctor -v reported a problem; review it before building."
}
Write-Host "Run: flutter clean; flutter pub get; flutter run -d f7d152a --dart-define-from-file=local_gateway.json"
