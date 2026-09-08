<#
  Publish the fortis.rest static site  (repo:  site\ )  to Cloudflare Pages.

  ---------------------------------------------------------------------------
  ONE-TIME SETUP
  ---------------------------------------------------------------------------
  1. Authenticate wrangler once, either:
        npx wrangler login                       # browser OAuth, remembered
     or set a scoped API token in your environment (survives reboots if you
     use `setx`):
        $env:CLOUDFLARE_API_TOKEN = "<token>"    # perms: "Cloudflare Pages: Edit"

  2. First run of this script creates the Pages project (default name
     "fortis-rest"). Afterwards, in the Cloudflare dashboard:
        Workers & Pages -> fortis-rest -> Custom domains
        -> add  fortis.rest  and  www.fortis.rest
     (Leave api.fortis.rest alone - that's the cloudflared tunnel.)

     Already made the project under a different name? Pass  -Project <name>.

  ---------------------------------------------------------------------------
  EVERY TIME
  ---------------------------------------------------------------------------
        .\deploy\publish-site.ps1
     Uploads the current contents of site\ as a production deployment.
     -Preview  deploys a throwaway preview build instead (its own URL).
#>
[CmdletBinding()]
param(
    [string]$Project = 'fortis-rest',
    [string]$Branch  = 'main',
    [switch]$Preview
)

$ErrorActionPreference = 'Stop'
# npx / git non-zero exits are checked explicitly below via $LASTEXITCODE - don't
# let PowerShell 7 turn a benign one (e.g. "project already exists") into a fatal.
$PSNativeCommandUseErrorActionPreference = $false
$repo = Split-Path -Parent $PSScriptRoot
$dir  = Join-Path $repo 'site'

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw 'Node.js is required - https://nodejs.org'
}
if (-not (Test-Path (Join-Path $dir 'index.html'))) {
    throw "Can't find $dir\index.html - run this from a checkout of the fortis repo."
}

# --- guard: unfilled placeholders in the site HTML -------------------------
$holes = Select-String -Path (Join-Path $dir '*.html') `
    -Pattern '\[(DATE|registered address|operating entity)\]' -ErrorAction SilentlyContinue
if ($holes) {
    Write-Warning 'site HTML still has unfilled placeholders:'
    $holes | ForEach-Object { Write-Host ("  {0}:{1}  {2}" -f $_.Filename, $_.LineNumber, $_.Line.Trim()) }
    if ((Read-Host 'Deploy anyway? (y/N)') -ne 'y') { return }
}

if ($Preview -and $Branch -eq 'main') { $Branch = 'preview' }
$isProd = $Branch -eq 'main'

# --- commit metadata -> shown on the deployment in the dashboard ----------
$sha   = (& git -C $repo rev-parse --short HEAD    2>$null)
$msg   = (& git -C $repo log -1 --pretty=format:%s 2>$null)
$dirty = if (& git -C $repo status --porcelain 2>$null) { 'true' } else { 'false' }

Write-Host ''
Write-Host "  project : $Project"
Write-Host "  folder  : $dir"
Write-Host ("  target  : {0}" -f $(if ($isProd) { 'production (fortis.rest)' } else { "preview  (branch '$Branch')" }))
Write-Host ("  commit  : {0} {1}{2}" -f $sha, $msg, $(if ($dirty -eq 'true') { '   [+ uncommitted changes]' }))
Write-Host ''

$wr = @('--yes', 'wrangler@4')

$deploy = @(
    'pages', 'deploy', $dir,
    '--project-name', $Project,
    '--branch',       $Branch,
    '--commit-dirty', $dirty
)
if ($sha) { $deploy += @('--commit-hash',    $sha) }
if ($msg) { $deploy += @('--commit-message', $msg) }

# Run from the repo root so wrangler's .wrangler\ working dir always lands in one
# predictable, git-ignored place regardless of where this script was invoked from.
Push-Location $repo
try {
    # Create the Pages project only if it doesn't exist yet. (Calling
    # `pages project create` on an existing project errors out.)
    $projects = (& npx @wr pages project list 2>$null | Out-String)
    if ($LASTEXITCODE -eq 0 -and $projects -notmatch [regex]::Escape($Project)) {
        Write-Host "  creating Pages project '$Project' ..."
        & npx @wr pages project create $Project --production-branch main | Out-Null
    }

    & npx @wr @deploy
    $rc = $LASTEXITCODE
}
finally { Pop-Location }

if ($rc -ne 0) {
    Write-Host ''
    Write-Warning "Deploy failed (exit $rc)."
    Write-Host '  - auth?     run once:  npx wrangler login'
    Write-Host "              or set `$env:CLOUDFLARE_API_TOKEN (perm: 'Cloudflare Pages: Edit')"
    Write-Host '  - project?  list yours: npx wrangler pages project list'
    Write-Host '              then re-run with  -Project <name>'
    exit $rc
}

Write-Host ''
if ($isProd) {
    Write-Host '  Done. https://fortis.rest/ updates within ~30s.' -ForegroundColor Green
} else {
    Write-Host '  Preview deployed - see the *.pages.dev URL printed above.' -ForegroundColor Green
}
