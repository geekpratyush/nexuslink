@echo off
rem NexusLink bootstrap for Windows — downloads the app from your Artifactory once, caches it,
rem and runs it. The PowerShell half below does the work; this wrapper just hands over to it so
rem users can double-click the .cmd or run it from a command prompt.
rem
rem   nexuslink.cmd                    run (downloading on the first run)
rem   nexuslink.cmd --update           force a re-download
rem   nexuslink.cmd --offline          never touch the network; fail if nothing is cached
rem   nexuslink.cmd --version 1.2.0    run a specific version
rem   nexuslink.cmd --list             show what is cached
rem   nexuslink.cmd --where            print the jar that would run, and exit
rem
rem Configuration (environment, or %USERPROFILE%\.nexuslink\bootstrap.conf of KEY=VALUE lines):
rem   NEXUSLINK_REPO_URL, NEXUSLINK_VERSION, NEXUSLINK_USER, NEXUSLINK_TOKEN, NEXUSLINK_HOME, JAVA_HOME
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$src = Get-Content '%~f0' -Raw; $ps = $src.Substring($src.IndexOf('#<#POWERSHELL#>#')); Invoke-Expression $ps" %*
exit /b %ERRORLEVEL%

#<#POWERSHELL#>#
$ErrorActionPreference = 'Stop'
$GroupPath  = 'com/nexuslink'
$Artifact   = 'nexuslink-app'
$Classifier = 'all'
$MinJava    = 21

function Fail($message) { Write-Host "nexuslink: $message" -ForegroundColor Red; exit 1 }

# ---- configuration -------------------------------------------------------------------------
$nexusHome = if ($env:NEXUSLINK_HOME) { $env:NEXUSLINK_HOME } else { Join-Path $env:USERPROFILE '.nexuslink' }
$conf = Join-Path $nexusHome 'bootstrap.conf'
if (Test-Path $conf) {
    # A config file lets an admin ship one pre-pointed script; the environment still wins.
    Get-Content $conf | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $key = $Matches[1]; $val = $Matches[2].Trim()
            if (-not [Environment]::GetEnvironmentVariable($key)) {
                [Environment]::SetEnvironmentVariable($key, $val)
            }
        }
    }
}

$repoUrl = $env:NEXUSLINK_REPO_URL
$version = if ($env:NEXUSLINK_VERSION) { $env:NEXUSLINK_VERSION } else { 'RELEASE' }
$cache   = Join-Path $nexusHome 'runtime'
$update  = $false; $offline = $false; $action = 'run'; $appArgs = @()

for ($i = 0; $i -lt $args.Count; $i++) {
    switch ($args[$i]) {
        '--update'  { $update = $true }
        '--offline' { $offline = $true }
        '--list'    { $action = 'list' }
        '--where'   { $action = 'where' }
        '--version' { $i++; $version = $args[$i] }
        '--repo'    { $i++; $repoUrl = $args[$i] }
        '--help'    { $action = 'help' }
        default     { $appArgs += $args[$i] }
    }
}

if ($action -eq 'help') {
    Get-Content $PSCommandPath -ErrorAction SilentlyContinue | Select-Object -First 16 | ForEach-Object { $_ -replace '^rem ?', '' }
    exit 0
}

New-Item -ItemType Directory -Force -Path $cache | Out-Null

if ($action -eq 'list') {
    $jars = Get-ChildItem -Path $cache -Filter "$Artifact-*.jar" -ErrorAction SilentlyContinue
    if (-not $jars) { Write-Host "nothing cached in $cache" } else {
        $jars | ForEach-Object { "{0}  {1:N0} MB" -f $_.Name, ($_.Length / 1MB) }
    }
    exit 0
}

# ---- java ------------------------------------------------------------------------------------
$javaBin = 'java'
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $javaBin = Join-Path $env:JAVA_HOME 'bin\java.exe'
}
try { $versionOutput = & $javaBin -version 2>&1 | Select-Object -First 1 }
catch { Fail "no Java found. NexusLink needs Java $MinJava or newer on PATH, or JAVA_HOME set." }
if ($versionOutput -match 'version "(\d+)') {
    if ([int]$Matches[1] -lt $MinJava) {
        Fail "Java $($Matches[1]) found, but NexusLink needs $MinJava or newer. Set JAVA_HOME to a newer JDK."
    }
}

# ---- download helpers --------------------------------------------------------------------------
function Get-AuthHeaders {
    if ($env:NEXUSLINK_USER) {
        $pair  = "$($env:NEXUSLINK_USER):$($env:NEXUSLINK_TOKEN)"
        $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
        return @{ Authorization = "Basic $basic" }
    }
    if ($env:NEXUSLINK_TOKEN) { return @{ Authorization = "Bearer $($env:NEXUSLINK_TOKEN)" } }
    return @{}
}

function Fetch($url, $dest) {
    Invoke-WebRequest -Uri $url -OutFile $dest -Headers (Get-AuthHeaders) -UseBasicParsing
}

function Resolve-Version {
    $meta = Join-Path $cache '.metadata.xml'
    try { Fetch "$repoUrl/$GroupPath/$Artifact/maven-metadata.xml" $meta }
    catch { Fail "could not read maven-metadata.xml from $repoUrl — check NEXUSLINK_REPO_URL and your credentials" }
    $xml = [xml](Get-Content $meta)
    $tag = if ($version -eq 'LATEST') { $xml.metadata.versioning.latest } else { $xml.metadata.versioning.release }
    if (-not $tag) { $tag = ($xml.metadata.versioning.versions.version | Select-Object -Last 1) }
    if (-not $tag) { Fail "the repository lists no versions of $Artifact" }
    return $tag
}

# ---- resolve the version and the cached jar ----------------------------------------------------
if ($version -in @('RELEASE', 'LATEST')) {
    if ($offline) {
        # Offline cannot ask the repository what "latest" means, so it uses the newest cached jar.
        $newest = Get-ChildItem -Path $cache -Filter "$Artifact-*.jar" -ErrorAction SilentlyContinue |
                  Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $newest) { Fail "nothing cached in $cache, and --offline was requested" }
        $jar = $newest.FullName
        $version = $newest.BaseName -replace "^$Artifact-", ''
    } else {
        if (-not $repoUrl) { Fail 'set NEXUSLINK_REPO_URL to your Artifactory repository (see --help)' }
        $version = Resolve-Version
        $jar = Join-Path $cache "$Artifact-$version.jar"
    }
} else {
    $jar = Join-Path $cache "$Artifact-$version.jar"
}

# ---- download when needed ----------------------------------------------------------------------
if ($update -or -not (Test-Path $jar)) {
    if ($offline) {
        if (-not (Test-Path $jar)) { Fail "$Artifact $version is not cached, and --offline was requested" }
    } else {
        if (-not $repoUrl) { Fail 'set NEXUSLINK_REPO_URL to your Artifactory repository (see --help)' }
        $url = "$repoUrl/$GroupPath/$Artifact/$version/$Artifact-$version-$Classifier.jar"
        $tmp = "$jar.part"
        Write-Host "nexuslink: downloading $Artifact $version..."
        try { Fetch $url $tmp } catch { Fail "download failed: $url" }

        # Verify against the repository's checksum when it publishes one — a truncated download that
        # still 'succeeds' is exactly what this catches.
        $verified = $false
        try {
            Fetch "$url.sha256" "$tmp.sha256"
            $expected = (Get-Content "$tmp.sha256" -Raw).Trim().Substring(0, 64)
            $actual   = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
            Remove-Item "$tmp.sha256" -Force
            if ($actual -ne $expected.ToLower()) {
                Remove-Item $tmp -Force
                Fail "checksum mismatch for $Artifact $version — the download does not match the repository"
            }
            $verified = $true
        } catch {
            if (Test-Path "$tmp.sha256") { Remove-Item "$tmp.sha256" -Force }
        }
        if (-not $verified) { Write-Host 'nexuslink: no .sha256 published for this artifact — skipping checksum verification' }
        Move-Item $tmp $jar -Force
        Write-Host "nexuslink: cached at $jar"
    }
}

if (-not (Test-Path $jar)) { Fail "$Artifact $version is not available" }
if ($action -eq 'where') { Write-Output $jar; exit 0 }

$javaOpts = if ($env:NEXUSLINK_JAVA_OPTS) { $env:NEXUSLINK_JAVA_OPTS -split ' ' } else { @() }
& $javaBin @javaOpts -jar $jar @appArgs
exit $LASTEXITCODE
