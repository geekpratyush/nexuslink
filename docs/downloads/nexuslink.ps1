<#
    NexusLink bootstrap for Windows (PowerShell).

    Downloads the app once from your repository, caches it, and runs it. Every run after the first
    is offline and instant. Run this directly, or use nexuslink.bat from a plain command prompt.

        .\nexuslink.ps1 --help     every option
#>
$ErrorActionPreference = 'Stop'

$GroupPath  = 'com/nexuslink'
$Artifact   = 'nexuslink-app'
$Classifier = 'all'
$MinJava    = 21

function Show-Usage {
@'
NexusLink - downloads the app once from your repository, caches it, and runs it.

USAGE
  nexuslink.ps1 [options] [-- app arguments]
  nexuslink.bat [options]              (same options, from a command prompt)

RUNNING
  (no options)          Run. Downloads only if this version is not already cached.
  --local               Run the build installed in ~/.m2 by dist/publish.sh - no repository needed.
  --offline             Never touch the network. Runs the newest cached build, or says there is none.
  --version <v>         Run a specific version (RELEASE, LATEST, or e.g. 1.2.0). Default: RELEASE.
  --repo <url>          Use this repository for one run, instead of NEXUSLINK_REPO_URL.

KEEPING IT UP TO DATE
  --update              Download this version again, replacing the cached copy.
  --fresh               Clear the cache first, then download and run. Use when a build looks wrong.

CACHE
  --list                Show what is cached, with sizes.
  --clean               Delete every cached build (the next run downloads again).
  --clean --version <v> Delete just that version.
  --where               Print the jar that would run, and exit.

OTHER
  --help                This text.
  -- <args>             Everything after -- is passed to the application.

CONFIGURATION  (environment, or KEY=VALUE lines in %USERPROFILE%\.nexuslink\bootstrap.conf)
  NEXUSLINK_REPO_URL    Maven repository base, e.g. https://artifactory.corp/artifactory/libs-release
                        Optional: with Maven already set up, the repository and its credentials are
                        read from ~/.m2\settings.xml (a mirror of *, else the first profile
                        repository, with the matching <server> for credentials).
  NEXUSLINK_VERSION     Version to run, or RELEASE / LATEST          (default: RELEASE)
  NEXUSLINK_USER        Repository username                          (optional)
  NEXUSLINK_TOKEN       Repository password or API token             (optional)
  NEXUSLINK_HOME        Cache directory                              (default: %USERPROFILE%\.nexuslink)
  NEXUSLINK_JAVA_OPTS   Extra JVM options, e.g. -Xmx2g
  JAVA_HOME             JDK to run with                              (default: java on PATH)

  To keep everything in the current folder instead of your profile:
      $env:NEXUSLINK_HOME = ".\nexuslink-cache"; .\nexuslink.ps1

EXAMPLES
  $env:NEXUSLINK_REPO_URL = "https://artifactory.corp/artifactory/libs-release"; .\nexuslink.ps1
  .\nexuslink.ps1 --update
  .\nexuslink.ps1 --version 1.2.0
  .\nexuslink.ps1 --fresh
  .\nexuslink.ps1 --clean
  .\nexuslink.ps1 --local

Requires Java 21 or newer.
'@ | Write-Host
}

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
$update  = $false; $offline = $false; $useLocal = $false; $action = 'run'; $appArgs = @()

for ($i = 0; $i -lt $args.Count; $i++) {
    switch ($args[$i]) {
        '--update'  { $update = $true }
        '--offline' { $offline = $true }
        '--local'   { $useLocal = $true }
        '--list'    { $action = 'list' }
        '--where'   { $action = 'where' }
        '--clean'   { $action = 'clean' }
        '--fresh'   { $action = 'fresh'; $update = $true }
        '--version' { $i++; $version = $args[$i] }
        '--repo'    { $i++; $repoUrl = $args[$i] }
        '--help'    { $action = 'help' }
        '-Help'     { $action = 'help' }
        '--'        { $appArgs += $args[($i + 1)..($args.Count - 1)]; $i = $args.Count }
        default     { $appArgs += $args[$i] }
    }
}

if ($action -eq 'help') { Show-Usage; exit 0 }

New-Item -ItemType Directory -Force -Path $cache | Out-Null

# ---- the repository, from Maven's own settings --------------------------------------------------
# Where a machine already builds with Maven, the repository and its credentials are configured once
# in settings.xml and nothing else should have to be set. Read them from there when neither --repo
# nor NEXUSLINK_REPO_URL says otherwise: a mirror that covers everything wins, then the first
# repository declared in a profile. Credentials come from the <server> whose id matches.

function Get-MavenSettingsPath {
    if ($env:MAVEN_SETTINGS -and (Test-Path $env:MAVEN_SETTINGS)) { return $env:MAVEN_SETTINGS }
    $user = Join-Path $HOME '.m2\settings.xml'
    if (Test-Path $user) { return $user }
    return $null
}

# Returns @{ Id = ...; Url = ... } for the repository Maven would use, or $null.
function Get-MavenSettingsRepo($settingsPath) {
    try { $xml = [xml](Get-Content -Raw -Path $settingsPath) } catch { return $null }
    $covering = @('*', 'external:*', 'central')
    foreach ($m in @($xml.settings.mirrors.mirror)) {
        if ($m -and $m.url -and ($m.mirrorOf -split ',' | Where-Object { $covering -contains $_.Trim() })) {
            return @{ Id = $m.id; Url = $m.url }
        }
    }
    foreach ($p in @($xml.settings.profiles.profile)) {
        foreach ($r in @($p.repositories.repository)) {
            if ($r -and $r.url) { return @{ Id = $r.id; Url = $r.url } }
        }
    }
    return $null
}

# Fills NEXUSLINK_USER / NEXUSLINK_TOKEN from the <server> with this id, if they are not already set.
function Set-MavenSettingsCredentials($settingsPath, $id) {
    if (-not $id -or $env:NEXUSLINK_USER -or $env:NEXUSLINK_TOKEN) { return }
    try { $xml = [xml](Get-Content -Raw -Path $settingsPath) } catch { return }
    foreach ($srv in @($xml.settings.servers.server)) {
        if ($srv -and $srv.id -eq $id) {
            $env:NEXUSLINK_USER  = $srv.username
            $env:NEXUSLINK_TOKEN = $srv.password
            return
        }
    }
}

$repoSource = if ($repoUrl -and $repoUrl -ne $env:NEXUSLINK_REPO_URL) { '--repo' } else { 'NEXUSLINK_REPO_URL' }
if (-not $repoUrl) {
    $settingsPath = Get-MavenSettingsPath
    if ($settingsPath) {
        $found = Get-MavenSettingsRepo $settingsPath
        if ($found) {
            Set-MavenSettingsCredentials $settingsPath $found.Id
            $repoUrl = $found.Url
            $repoSource = $settingsPath
        }
    }
}

# ---- cache management ---------------------------------------------------------------------------

if ($action -eq 'clean' -or $action -eq 'fresh') {
    $pattern = if ($version -in @('RELEASE', 'LATEST')) { "$Artifact-*.jar*" } else { "$Artifact-$version.jar*" }
    $removed = Get-ChildItem -Path $cache -Filter $pattern -ErrorAction SilentlyContinue
    $removed | Remove-Item -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $cache '.metadata.xml') -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $cache '.snapshot-metadata.xml') -Force -ErrorAction SilentlyContinue
    Write-Host "nexuslink: removed $($removed.Count) cached file(s) from $cache"
    if ($action -eq 'clean') { exit 0 }
    $action = 'run'      # --fresh carries on and downloads again
}

if ($action -eq 'list') {
    $jars = Get-ChildItem -Path $cache -Filter "$Artifact-*.jar" -ErrorAction SilentlyContinue
    if (-not $jars) { Write-Host "nothing cached in $cache" }
    else { $jars | ForEach-Object { "{0}  {1:N0} MB" -f $_.Name, ($_.Length / 1MB) } }
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

$javaOpts = if ($env:NEXUSLINK_JAVA_OPTS) { $env:NEXUSLINK_JAVA_OPTS -split ' ' } else { @() }

# ---- local ~/.m2 --------------------------------------------------------------------------------
# The developer loop: dist/publish.sh installs the build into ~/.m2, and this runs that copy with no
# repository involved. Also the fallback when nothing else is configured.

$m2Repo = Join-Path (Join-Path $env:USERPROFILE '.m2\repository') ($GroupPath -replace '/', '\')
$m2Repo = Join-Path $m2Repo $Artifact

function Get-LocalJar($want) {
    if (-not (Test-Path $m2Repo)) { return $null }
    if ($want -and $want -notin @('RELEASE', 'LATEST')) {
        $candidate = Join-Path (Join-Path $m2Repo $want) "$Artifact-$want-$Classifier.jar"
        if (Test-Path $candidate) { return $candidate }
        return $null
    }
    $newest = Get-ChildItem -Path $m2Repo -Recurse -Filter "$Artifact-*-$Classifier.jar" -ErrorAction SilentlyContinue |
              Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($newest) { return $newest.FullName }
    return $null
}

if ($useLocal) {
    $jar = Get-LocalJar $version
    if (-not $jar) { Fail "nothing installed in $m2Repo - run dist/publish.sh --local first" }
    if ($action -eq 'where') { Write-Output $jar; exit 0 }
    & $javaBin @javaOpts -jar $jar @appArgs
    exit $LASTEXITCODE
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

# The newest jar in the cache, or $null. Used when the repository cannot be reached.
function Get-NewestCached {
    $newest = Get-ChildItem -Path $cache -Filter "$Artifact-*.jar" -ErrorAction SilentlyContinue |
              Sort-Object LastWriteTime -Descending | Select-Object -First 1
    return $newest
}

# Resolves RELEASE / LATEST against the repository's maven-metadata.xml.
# Returns $null when the repository cannot be read, so the caller can fall back to the cache.
function Resolve-Version {
    $meta = Join-Path $cache '.metadata.xml'
    try { Fetch "$repoUrl/$GroupPath/$Artifact/maven-metadata.xml" $meta }
    catch { return $null }
    $xml = [xml](Get-Content $meta)
    $tag = if ($version -eq 'LATEST') { $xml.metadata.versioning.latest } else { $xml.metadata.versioning.release }
    if (-not $tag) { $tag = ($xml.metadata.versioning.versions.version | Select-Object -Last 1) }
    if (-not $tag) { return $null }
    return $tag
}

# A SNAPSHOT is published under a timestamped filename, recorded in the version-level metadata.
function Resolve-SnapshotFile($v) {
    $meta = Join-Path $cache '.snapshot-metadata.xml'
    try { Fetch "$repoUrl/$GroupPath/$Artifact/$v/maven-metadata.xml" $meta } catch { return $null }
    $xml = [xml](Get-Content $meta)
    $entry = $xml.metadata.versioning.snapshotVersions.snapshotVersion |
             Where-Object { $_.classifier -eq $Classifier } | Select-Object -First 1
    if ($entry) { return "$Artifact-$($entry.value)-$Classifier.jar" }
    return $null
}

# ---- resolve the version and the cached jar ----------------------------------------------------

if ($version -in @('RELEASE', 'LATEST')) {
    if ($offline) {
        $newest = Get-NewestCached
        if (-not $newest) { Fail "nothing cached in $cache, and --offline was requested" }
        $jar = $newest.FullName
        $version = $newest.BaseName -replace "^$Artifact-", ''
    } elseif (-not $repoUrl) {
        # No repository, but this machine may have built the project - use that rather than failing.
        $fallback = Get-LocalJar $null
        if (-not $fallback) {
            Fail 'no repository found in ~/.m2/settings.xml - set NEXUSLINK_REPO_URL or pass --repo, or run dist/publish.sh --local first (see --help)'
        }
        Write-Host 'nexuslink: no repository configured - running the local ~/.m2 build'
        if ($action -eq 'where') { Write-Output $fallback; exit 0 }
        & $javaBin @javaOpts -jar $fallback @appArgs
        exit $LASTEXITCODE
    } else {
        $resolved = Resolve-Version
        if ($resolved) {
            $version = $resolved
            $jar = Join-Path $cache "$Artifact-$version.jar"
        } else {
            # The repository is unreachable - off the VPN, or down. A machine that has run before
            # already has the application; start it rather than refusing to work.
            $newest = Get-NewestCached
            if (-not $newest) {
                Fail "could not read maven-metadata.xml from $repoUrl (from $repoSource), and nothing is cached in $cache - check the repository and your credentials"
            }
            $jar = $newest.FullName
            $version = $newest.BaseName -replace "^$Artifact-", ''
            Write-Host "nexuslink: $repoUrl is unreachable - running the cached $version"
        }
    }
} else {
    $jar = Join-Path $cache "$Artifact-$version.jar"
}

# ---- download when needed ----------------------------------------------------------------------

if ($update -or -not (Test-Path $jar)) {
    if ($offline) {
        if (-not (Test-Path $jar)) { Fail "$Artifact $version is not cached, and --offline was requested" }
    } else {
        if (-not $repoUrl) { Fail 'no repository found in ~/.m2/settings.xml - set NEXUSLINK_REPO_URL or pass --repo (see --help)' }
        $file = "$Artifact-$version-$Classifier.jar"
        if ($version -like '*-SNAPSHOT') {
            $snapshotFile = Resolve-SnapshotFile $version
            if ($snapshotFile) { $file = $snapshotFile }
        }
        $url = "$repoUrl/$GroupPath/$Artifact/$version/$file"
        $tmp = "$jar.part"
        Write-Host "nexuslink: downloading $Artifact $version..."
        try { Fetch $url $tmp } catch { Fail "download failed: $url" }

        # Verify against the repository's checksum - a truncated download that still 'succeeds' is
        # exactly what this catches. Maven writes .sha1; Artifactory adds .sha256 server-side.
        $verified = $null
        foreach ($algorithm in @('sha256', 'sha1')) {
            try {
                Fetch "$url.$algorithm" "$tmp.sum"
                $expected = (Get-Content "$tmp.sum" -Raw).Trim()
                $actual   = (Get-FileHash $tmp -Algorithm $algorithm.ToUpper()).Hash
                Remove-Item "$tmp.sum" -Force -ErrorAction SilentlyContinue
                if ($actual.ToLower() -ne $expected.Substring(0, $actual.Length).ToLower()) {
                    Remove-Item $tmp -Force
                    Fail "checksum mismatch for $Artifact $version - the download does not match the repository"
                }
                $verified = $algorithm
                break
            } catch {
                Remove-Item "$tmp.sum" -Force -ErrorAction SilentlyContinue
            }
        }
        if (-not $verified) {
            Write-Host 'nexuslink: the repository publishes no checksum for this artifact - skipping verification'
        }
        Move-Item $tmp $jar -Force
        Write-Host "nexuslink: cached at $jar"
    }
}

if (-not (Test-Path $jar)) { Fail "$Artifact $version is not available" }
if ($action -eq 'where') { Write-Output $jar; exit 0 }

& $javaBin @javaOpts -jar $jar @appArgs
exit $LASTEXITCODE
