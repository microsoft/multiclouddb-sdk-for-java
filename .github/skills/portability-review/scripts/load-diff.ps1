#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Load a diff for portability review and write it to a temp file.

.DESCRIPTION
    Auto-detects the canonical-main ref (upstream/main -> origin/main ->
    repo default branch), explicitly fetches it, then computes the diff
    using `git merge-base` so the comparison is against the true fork
    point and not whatever the local main happens to point at.

    Supports three scopes:
      - branch   (default) -- current branch vs canonical main
      - staged   -- git diff --staged
      - pr       -- gh pr diff <Pr> against the PR's base branch

    Writes the diff and a stat summary to temp files and prints their
    paths on stdout, one per line:
        DIFF_PATH=<path>
        STAT_PATH=<path>
        BASE_REF=<ref>
        DIFF_BYTES=<size>

    The calling agent reads the temp files via read_file.

.PARAMETER Scope
    branch | staged | pr. Default: branch.

.PARAMETER Pr
    PR number. Required when -Scope is `pr`.

.PARAMETER Base
    Explicit base ref to compare against. Overrides auto-detection.
    Ignored for -Scope staged.

.PARAMETER Repo
    Override the GitHub repo (owner/repo) for -Scope pr. Defaults to
    the `upstream` remote if present, else `origin`.

.PARAMETER NoFetch
    Skip the `git fetch` step. Use only when offline; the diff may then
    be computed against a stale base.

.EXAMPLE
    ./load-diff.ps1
    # Branch vs auto-detected canonical main

.EXAMPLE
    ./load-diff.ps1 -Scope staged

.EXAMPLE
    ./load-diff.ps1 -Scope pr -Pr 80
#>

[CmdletBinding()]
param(
    [ValidateSet('branch', 'staged', 'pr')]
    [string]$Scope = 'branch',

    [int]$Pr,

    [string]$Base,

    [string]$Repo,

    [switch]$NoFetch
)

$ErrorActionPreference = 'Stop'


function Write-Status {
    param([string]$Message)
    # Route status to stderr so callers parsing stdout (KEY=VALUE) aren't disturbed.
    [Console]::Error.WriteLine($Message)
}

function Get-RepoRoot {
    $root = git rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $root) {
        throw "Not inside a git repository. Run this script from within a git working tree."
    }
    return $root
}

function Test-Ref {
    param([string]$Ref)
    git rev-parse --verify --quiet $Ref *> $null
    return ($LASTEXITCODE -eq 0)
}

function Get-DefaultRemote {
    if (Test-Ref 'refs/remotes/upstream/HEAD') { return 'upstream' }
    if (git remote | Where-Object { $_ -eq 'upstream' }) { return 'upstream' }
    return 'origin'
}

function Get-DefaultBranch {
    param([string]$Remote)
    # Try the symbolic ref first (cheap, works when remote HEAD is set)
    $headRef = git symbolic-ref --quiet "refs/remotes/$Remote/HEAD" 2>$null
    if ($LASTEXITCODE -eq 0 -and $headRef) {
        return ($headRef -replace "^refs/remotes/$Remote/", '')
    }
    # Fall back to remote query
    $headLine = git remote show $Remote 2>$null | Where-Object { $_ -match '^\s*HEAD branch:' } | Select-Object -First 1
    if ($headLine -and $headLine -match 'HEAD branch:\s*(\S+)') {
        return $matches[1]
    }
    return 'main'
}

function Resolve-CanonicalBase {
    param([string]$Override)
    if ($Override) { return $Override }

    $remote = Get-DefaultRemote
    $branch = Get-DefaultBranch -Remote $remote
    $candidates = @(
        "upstream/$branch",
        "origin/$branch",
        $branch
    )
    foreach ($cand in $candidates) {
        if (Test-Ref $cand) { return $cand }
    }
    throw "Could not resolve canonical base ref. Tried: $($candidates -join ', '). Pass -Base explicitly."
}

function Invoke-Fetch {
    param([string]$BaseRef, [bool]$Skip)
    if ($Skip) {
        Write-Status "Skipping git fetch (-NoFetch)."
        return
    }
    # Refresh the resolved base. We split "<remote>/<branch>" to fetch the specific ref.
    if ($BaseRef -match '^(?<remote>[^/]+)/(?<branch>.+)$') {
        $remote = $matches.remote
        $branch = $matches.branch
        if (git remote | Where-Object { $_ -eq $remote }) {
            Write-Status "Fetching $remote/$branch..."
            git fetch --quiet $remote $branch 2>$null
            if ($LASTEXITCODE -ne 0) {
                Write-Status "  (fetch reported a non-zero exit; continuing with possibly stale ref)"
            }
        }
    } else {
        # Local branch -- fetch from default remote so it isn't stale either.
        $remote = Get-DefaultRemote
        if (git remote | Where-Object { $_ -eq $remote }) {
            Write-Status "Fetching $remote/$BaseRef..."
            git fetch --quiet $remote $BaseRef 2>$null
        }
    }
}

function Write-DiffArtifact {
    param([string]$DiffContent, [string]$StatContent, [string]$BaseRef)
    $tempDir = if ($env:TEMP) { $env:TEMP } else { '/tmp' }
    $tag = (Get-Date -Format 'yyyyMMddHHmmss')
    $diffPath = Join-Path $tempDir "portability-review-diff-$tag.txt"
    $statPath = Join-Path $tempDir "portability-review-stat-$tag.txt"
    Set-Content -Path $diffPath -Value $DiffContent -Encoding UTF8 -NoNewline
    Set-Content -Path $statPath -Value $StatContent -Encoding UTF8 -NoNewline

    $bytes = (Get-Item $diffPath).Length
    Write-Output "DIFF_PATH=$diffPath"
    Write-Output "STAT_PATH=$statPath"
    Write-Output "BASE_REF=$BaseRef"
    Write-Output "DIFF_BYTES=$bytes"
}

# --- main ---

$repoRoot = Get-RepoRoot
Set-Location $repoRoot

switch ($Scope) {
    'staged' {
        $diff = git --no-pager diff --staged 2>&1 | Out-String
        $stat = git --no-pager diff --staged --stat 2>&1 | Out-String
        Write-DiffArtifact -DiffContent $diff -StatContent $stat -BaseRef '(staged)'
    }
    'pr' {
        if (-not $Pr) {
            throw "-Pr <number> is required when -Scope is 'pr'."
        }
        if (-not $Repo) {
            # Derive owner/repo from upstream, falling back to origin.
            $remote = Get-DefaultRemote
            $url = git remote get-url $remote 2>$null
            if ($url -match 'github\.com[:/](?<owner>[^/]+)/(?<repo>[^/.]+)(\.git)?$') {
                $Repo = "$($matches.owner)/$($matches.repo)"
            } else {
                throw "Could not derive owner/repo from $remote remote ($url). Pass -Repo explicitly."
            }
        }

        $prBase = & gh pr view $Pr --repo $Repo --json baseRefName --jq '.baseRefName' 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $prBase) {
            throw "Failed to query PR $Pr from $Repo. Is 'gh auth' set up?"
        }
        # Build a proper stat summary (filename + insertions/deletions) so STAT_PATH
        # has the same shape as `git diff --stat` for branch/staged scopes.
        $filesJson = & gh api "repos/$Repo/pulls/$Pr/files" --paginate 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $filesJson) {
            throw "Failed to query PR $Pr files from $Repo via gh api."
        }
        $files = $filesJson | ConvertFrom-Json
        $statLines = @()
        $totalAdd = 0
        $totalDel = 0
        foreach ($f in $files) {
            $bar = ('+' * [Math]::Min($f.additions, 40)) + ('-' * [Math]::Min($f.deletions, 40))
            $statLines += (' {0,-60} | {1,5} {2}' -f $f.filename, ($f.additions + $f.deletions), $bar)
            $totalAdd += $f.additions
            $totalDel += $f.deletions
        }
        $statLines += " $($files.Count) files changed, $totalAdd insertions(+), $totalDel deletions(-)"
        $stat = ($statLines -join [Environment]::NewLine)
        $diff = & gh pr diff $Pr --repo $Repo 2>&1 | Out-String
        Write-DiffArtifact -DiffContent $diff -StatContent $stat -BaseRef "$Repo PR #$Pr (base: $prBase)"
    }
    default {
        $baseRef = Resolve-CanonicalBase -Override $Base
        Invoke-Fetch -BaseRef $baseRef -Skip:$NoFetch

        # Use merge-base so the diff is "changes on this branch since fork point",
        # not "everything that differs between HEAD and BASE".
        $mergeBase = git merge-base $baseRef HEAD 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $mergeBase) {
            throw "Could not compute merge-base between $baseRef and HEAD."
        }
        $stat = git --no-pager diff --stat "$mergeBase..HEAD" 2>&1 | Out-String
        $diff = git --no-pager diff "$mergeBase..HEAD" 2>&1 | Out-String
        Write-DiffArtifact -DiffContent $diff -StatContent $stat -BaseRef "$baseRef (merge-base $mergeBase)"
    }
}
