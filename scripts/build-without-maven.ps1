# Builds the all-in-one jar with nothing but a JDK (25 or newer). Tests are not run:
# use Maven for that ("mvn verify").
#
#   powershell -ExecutionPolicy Bypass -File scripts\build-without-maven.ps1
#   java -jar dist\logdelta.jar
$ErrorActionPreference = 'Stop'

$Root = Resolve-Path (Join-Path $PSScriptRoot '..')
$Out = Join-Path $Root 'build\classes'
$Dist = Join-Path $Root 'dist'

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    Write-Error 'javac not found: install a JDK 25+'
}

if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Force -Path $Out, $Dist | Out-Null

Write-Host 'Compiling...'
$modules = 'logdelta-core', 'logdelta-cli', 'logdelta-gui', 'logdelta-app'
$sources = foreach ($m in $modules) {
    Get-ChildItem -Recurse -Filter *.java (Join-Path $Root "$m\src\main\java") | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
}
$sourceList = Join-Path $Root 'build\sources.txt'
Set-Content -Path $sourceList -Value $sources -Encoding ASCII
& javac --release 25 -encoding UTF-8 -d $Out "@$sourceList"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item -Recurse -Force (Join-Path $Root 'logdelta-gui\src\main\resources\*') $Out
New-Item -ItemType Directory -Force -Path (Join-Path $Out 'META-INF') | Out-Null
Copy-Item -Force (Join-Path $Root 'LICENSE') (Join-Path $Out 'META-INF\LICENSE')

Write-Host 'Packaging...'
$manifest = Join-Path $Root 'build\manifest.txt'
Set-Content -Path $manifest -Value "Main-Class: io.github.logdelta.app.Launcher`nImplementation-Title: LogDelta`n" -Encoding ASCII
& jar --create --file (Join-Path $Dist 'logdelta.jar') --manifest $manifest -C $Out .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Done: $Dist\logdelta.jar"
