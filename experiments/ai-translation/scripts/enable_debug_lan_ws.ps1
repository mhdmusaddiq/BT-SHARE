# Update only Flutter-generated debug manifest; no release cleartext.
$ErrorActionPreference = "Stop"
$project = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $project "android\app\src\debug\AndroidManifest.xml"
if (!(Test-Path $manifestPath)) {
  throw "Missing debug manifest; run flutter create --platforms=android ."
}
[xml]$xml = Get-Content -Raw $manifestPath
$app = $xml.SelectSingleNode("/manifest/application")
if ($null -eq $app) {
  $app = $xml.CreateElement("application")
  [void]$xml.DocumentElement.AppendChild($app)
}
$app.SetAttribute("usesCleartextTraffic", "http://schemas.android.com/apk/res/android", "true")
$xml.Save($manifestPath)
Write-Output "Debug-only private LAN cleartext enabled; release unchanged."
