# SPDX-License-Identifier: GPL-3.0-or-later
# Used by mvnw.cmd: makes sure the Maven named in maven-wrapper.properties is installed under
# %USERPROFILE%\.m2\wrapper\dists (or $env:MAVEN_USER_HOME) and prints its folder.
$ErrorActionPreference = 'Stop'
$props = Join-Path $PSScriptRoot 'maven-wrapper.properties'
$values = @{}
foreach ($line in Get-Content $props) {
    if ($line -match '^\s*([^#=\s][^=]*?)\s*=\s*(.*)$') { $values[$Matches[1]] = $Matches[2].Trim() }
}
$url = $values['distributionUrl']
if (-not $url) { Write-Error "mvnw: no distributionUrl in $props"; exit 1 }
$archive = $url.Substring($url.LastIndexOf('/') + 1)
if ($archive.EndsWith('-bin.tar.gz')) { $name = $archive.Substring(0, $archive.Length - 11) }
elseif ($archive.EndsWith('-bin.zip')) { $name = $archive.Substring(0, $archive.Length - 8) }
else { Write-Error "mvnw: unsupported distribution archive $archive"; exit 1 }

$userHome = if ($env:MAVEN_USER_HOME) { $env:MAVEN_USER_HOME } else { Join-Path $env:USERPROFILE '.m2' }
$sha = [System.Security.Cryptography.SHA256]::Create()
$urlHash = -join ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($url))[0..7] | ForEach-Object { $_.ToString('x2') })
$dists = Join-Path $userHome "wrapper\dists\$name\$urlHash"
$mavenHome = Join-Path $dists $name
if (-not (Test-Path (Join-Path $mavenHome 'bin\mvn.cmd'))) {
    New-Item -ItemType Directory -Force -Path $dists | Out-Null
    $tmp = Join-Path $dists ('download-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        [Console]::Error.WriteLine("mvnw: downloading $url")
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $file = Join-Path $tmp $archive
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $file
        $expected = $values['distributionSha256Sum']
        if ($expected) {
            $actual = (Get-FileHash -Algorithm SHA256 $file).Hash.ToLowerInvariant()
            if ($actual -ne $expected.ToLowerInvariant()) { throw "checksum mismatch for $archive (expected $expected, got $actual)" }
        }
        if ($archive.EndsWith('.zip')) { Expand-Archive -Path $file -DestinationPath $tmp }
        else { tar -xzf $file -C $tmp; if ($LASTEXITCODE -ne 0) { throw "cannot extract $archive" } }
        if (-not (Test-Path (Join-Path $mavenHome 'bin\mvn.cmd'))) {
            Move-Item -Path (Join-Path $tmp $name) -Destination $mavenHome
        }
    } catch {
        [Console]::Error.WriteLine("mvnw: $_")
        exit 1
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}
Write-Output $mavenHome
