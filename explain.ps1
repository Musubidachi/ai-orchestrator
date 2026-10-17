<#
.SYNOPSIS
  Reads Forge's own documentation out loud.

.DESCRIPTION
  Explains what Forge is, or one package or class of it, using the notes already in the source:
  ARCHITECTURE.md, each module's CLAUDE.md, and the package-info.java files. Nothing is generated
  and no model is called, so this costs no quota, works offline, and says only what is written down.

  Deliberately outside Forge: it needs no jar, no build and no editor running. When Forge will not
  compile is exactly when you want something to tell you how it fits together.

  Speech comes from System.Speech, which ships with Windows. The transcript and the recording are
  written to logs\, so a walkthrough can be read, re-listened to, or sent somewhere.

.PARAMETER Topic
  A package (jobs.queue), a class (ReviewStep), or nothing for Forge as a whole.

.PARAMETER NoSpeak
  Write the transcript and the recording without playing anything.

.PARAMETER Voice
  A installed voice name, such as "Microsoft Zira Desktop". Defaults to whatever Windows uses.

.EXAMPLE
  .\explain.ps1
  .\explain.ps1 jobs.queue
  .\explain.ps1 ReviewStep -NoSpeak
#>
param(
  [string] $Topic = "",
  [switch] $NoSpeak,
  [string] $Voice = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$forge = Join-Path $root "forge"

if (-not (Test-Path $forge)) {
  Write-Error "No forge directory beside this script: $forge"
}

# --------------------------------------------------------------- reading the notes

<#
  Javadoc, as a sentence.

  The notes are written for a reader: comment markers, {@code ...} wrappers and HTML tags all say
  something on the page and nothing out loud, so they come out. What is left is the prose.
#>
function Convert-Javadoc([string] $text) {
  $out = $text -replace '(?m)^\s*/\*\*\s*', '' -replace '(?m)^\s*\*/\s*', '' -replace '(?m)^\s*\*\s?', ''
  $out = $out -replace '\{@code\s+([^}]*)\}', '$1' -replace '\{@link\s+([^}]*)\}', '$1'
  $out = $out -replace '<p>', ' ' -replace '</?b>', '' -replace '<[^>]+>', ''
  $out = $out -replace '&mdash;', ', ' -replace '&amp;', ' and '
  return (Convert-Typography $out)
}

<#
  Typography a voice cannot say.

  These notes are full of em dashes and curly quotes, which a synthesiser either skips or spells
  out. A comma where the dash was keeps the pause the sentence was written with.
#>
function Convert-Typography([string] $text) {
  # Escape sequences rather than the characters themselves: the regex engine reads —, and
  # the script then says what it means whatever encoding it is read in.
  $out = $text -replace '[—–]', ',' -replace '[‘’]', "'" -replace '[“”]', '"'
  $out = $out -replace '…', '...' -replace '[^\x00-\x7F]', ''
  return ($out -replace '\s+', ' ' -replace '\s+,', ',').Trim()
}

<# The first sentences of a markdown file, with its headings and formatting taken off. #>
function Convert-Markdown([string] $text) {
  $out = $text -replace '(?m)^#+\s*', '' -replace '\*\*', '' -replace '`', ''
  return (Convert-Typography $out)
}

function Get-PackageNote([string] $package) {
  $relative = "src\main\java\dev\fmangoni\forge\" + ($package -replace '\.', '\') + "\package-info.java"
  $found = Get-ChildItem -LiteralPath $forge -Directory |
    ForEach-Object { Join-Path $_.FullName $relative } |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1
  if (-not $found) { return $null }
  $text = Get-Content -LiteralPath $found -Raw -Encoding UTF8
  $text = $text -replace '(?s)\*\s*<p>Depends on:.*', '*/'
  return Convert-Javadoc $text
}

function Get-ClassNote([string] $name) {
  $file = Get-ChildItem -LiteralPath $forge -Recurse -File -Filter "$name.java" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -match '\\src\\main\\' } |
    Select-Object -First 1
  if (-not $file) { return $null }
  $text = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
  # The class's own javadoc: the last block comment before the type declaration.
  $match = [regex]::Match($text, '(?s)/\*\*(?<doc>.*?)\*/\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+)?(?:final\s+|abstract\s+)?(class|record|interface|enum)\s+' + [regex]::Escape($name))
  if (-not $match.Success) { return $null }
  $where = $file.FullName.Substring($forge.Length + 1) -replace '\\src\\main\\java\\dev\\fmangoni\\forge\\', ', in '
  $note = Convert-Javadoc ("/**" + $match.Groups["doc"].Value + "*/")
  return "$name. $note"
}

# --------------------------------------------------------------- what to say

function Get-Overview {
  $said = New-Object System.Collections.Generic.List[string]
  $said.Add("Forge. An unattended build pipeline that drives the Claude Code and Codex command line tools against a wishlist of project specs.")
  $said.Add("The one idea is that Forge decides whether a project is done, not the agent: every spec declares acceptance commands, and Forge runs them itself against the files on disk.")

  $modules = Get-ChildItem -LiteralPath $forge -Directory | Where-Object { $_.Name -like "forge-*" }
  $classes = Get-ChildItem -LiteralPath $forge -Recurse -File -Filter *.java |
    Where-Object { $_.FullName -notmatch '\\target\\' -and $_.FullName -match '\\src\\main\\' -and $_.Name -ne 'package-info.java' }
  $packages = Get-ChildItem -LiteralPath $forge -Recurse -File -Filter package-info.java |
    Where-Object { $_.FullName -notmatch '\\target\\' }
  $said.Add("It is $($modules.Count) Maven modules, $($packages.Count) packages and $($classes.Count) classes. A module may only use the modules below it, so the compiler refuses a dependency that points the wrong way.")

  foreach ($module in $modules) {
    $note = Join-Path $module.FullName "CLAUDE.md"
    if (Test-Path $note) {
      $lines = Get-Content -LiteralPath $note -Encoding UTF8
      $summary = ($lines | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne "" } | Select-Object -First 1)
      $said.Add("$($module.Name -replace '-', ' '). " + (Convert-Markdown $summary))
    }
  }

  # Only the rules table. ARCHITECTURE.md has several tables, and reading every row of all of them
  # aloud produces a paragraph that is technically accurate and impossible to follow.
  $rules = Join-Path $forge "ARCHITECTURE.md"
  if (Test-Path $rules) {
    $section = [regex]::Match((Get-Content -LiteralPath $rules -Raw -Encoding UTF8),
      '(?s)##\s*Rules the build enforces(?<body>.*?)(?:\r?\n##\s|\z)')
    if ($section.Success) {
      $forbids = $section.Groups["body"].Value -split '\r?\n' |
        Where-Object { $_ -match '^\|' -and $_ -notmatch '^\|\s*-+' -and $_ -notmatch '^\|\s*Test\s*\|' } |
        ForEach-Object { ($_ -split '\|')[2].Trim() } |
        Where-Object { $_ }
      if ($forbids) {
        $said.Add("The build refuses " + (Convert-Markdown ($forbids -join "; ")) + ".")
      }
    }
  }
  $said.Add("For where a change belongs, read ARCHITECTURE dot M D. Every package says what belongs in it in its own package-info file.")
  return ($said -join " ")
}

function Get-Narration {
  if (-not $Topic) { return Get-Overview }
  $note = Get-PackageNote $Topic
  if ($note) { return "The $Topic package. $note" }
  $note = Get-ClassNote $Topic
  if ($note) { return $note }
  return "There is no package or class called $Topic in Forge. Try a package such as jobs dot queue, or a class such as Review Step."
}

# --------------------------------------------------------------- saying it

$text = Get-Narration
$slug = "forge"
if ($Topic) { $slug = ($Topic -replace '[^\w.-]', '_') }
$logs = Join-Path $root "logs"
if (-not (Test-Path $logs)) { New-Item -ItemType Directory -Path $logs | Out-Null }
$transcript = Join-Path $logs "explain-$slug.md"
$recording = Join-Path $logs "explain-$slug.wav"

Set-Content -LiteralPath $transcript -Value $text -Encoding utf8
Write-Host $text
Write-Host ""
Write-Host "[explain] transcript: $transcript"

Add-Type -AssemblyName System.Speech
$speech = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
  if ($Voice) { $speech.SelectVoice($Voice) }
  # Recorded first, then played: one synthesis pass leaves both the sound and the file, and the
  # file is the point of asking for a walkthrough you can listen to later.
  $speech.SetOutputToWaveFile($recording)
  $speech.Speak($text)
  $speech.SetOutputToNull()
} finally {
  $speech.Dispose()
}
Write-Host "[explain] recording:  $recording"

if (-not $NoSpeak) {
  $player = New-Object System.Media.SoundPlayer $recording
  $player.PlaySync()
  $player.Dispose()
}
