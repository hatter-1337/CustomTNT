$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Java = Join-Path $Root "jdk-21.0.9\bin\javac.exe"
$Jar = Join-Path $Root "jdk-21.0.9\bin\jar.exe"
$Api = Join-Path $Root "libraries\io\papermc\paper\paper-api\1.21.4-R0.1-SNAPSHOT\paper-api-1.21.4-R0.1-SNAPSHOT.jar"
$Libraries = Get-ChildItem -Path (Join-Path $Root "libraries") -Recurse -Filter "*.jar" | ForEach-Object { $_.FullName }
$Classpath = (@($Api) + $Libraries) -join [IO.Path]::PathSeparator
$Out = Join-Path $PSScriptRoot "build\classes"
$Dist = Join-Path $PSScriptRoot "build\CustomTNT.jar"
$PluginsJar = Join-Path $Root "plugins\CustomTNT.jar"

New-Item -ItemType Directory -Force -Path $Out | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Dist) | Out-Null

$Sources = Get-ChildItem -Path (Join-Path $PSScriptRoot "src\main\java") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
& $Java -encoding UTF-8 -classpath $Classpath -d $Out $Sources
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Copy-Item -Path (Join-Path $PSScriptRoot "src\main\resources\*") -Destination $Out -Recurse -Force
& $Jar --create --file $Dist -C $Out .
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
Copy-Item -Path $Dist -Destination $PluginsJar -Force

Write-Host "Built $Dist"
Write-Host "Installed $PluginsJar"
