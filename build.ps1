# Builds Arch AI: src -> bin -> local.archi.ai_<version>.jar -> ArchAI_<version>.archiplugin
# ARCHI_HOME and JAVA_HOME (JDK 21+) may be given as parameters or environment variables.
param(
    [string]$ArchiHome = $(if ($env:ARCHI_HOME) { $env:ARCHI_HOME } else { 'C:\Program Files\Archi' }),
    [string]$JavaHome  = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Tool($name) {
    if ($JavaHome) { return Join-Path $JavaHome "bin\$name.exe" }
    return $name
}

$plugins = Join-Path $ArchiHome 'plugins'
$editor = Get-ChildItem $plugins -Directory -Filter 'com.archimatetool.editor_*' | Select-Object -First 1
$model  = Get-ChildItem $plugins -Directory -Filter 'com.archimatetool.model_*'  | Select-Object -First 1
if (-not $editor -or -not $model) { throw "Archi not found in $ArchiHome (use -ArchiHome or ARCHI_HOME)" }
$cp = "$plugins\*;$($editor.FullName)\com.archimatetool.editor.jar;$($model.FullName)\com.archimatetool.model.jar"

$version = (Select-String -Path META-INF\MANIFEST.MF -Pattern '^Bundle-Version:\s*(.+)$').Matches[0].Groups[1].Value.Trim()
$jar = "local.archi.ai_$version.jar"
$dist = "ArchAI_$version.archiplugin"

Remove-Item -Recurse -Force bin -ErrorAction SilentlyContinue
New-Item -ItemType Directory bin | Out-Null
$sources = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName
& (Tool javac) --release 21 -encoding UTF-8 -nowarn -d bin -cp $cp @sources
if ($LASTEXITCODE) { throw 'Compilation failed' }

& (Tool jar) cfm $jar META-INF/MANIFEST.MF plugin.xml -C bin .
if ($LASTEXITCODE) { throw 'jar failed' }

# .archiplugin is a zip with the bundle jar and an empty "archi-plugin" marker file
$tmp = Join-Path ([IO.Path]::GetTempPath()) "archi-ai-dist-$PID"
New-Item -ItemType Directory $tmp -Force | Out-Null
Copy-Item $jar $tmp
New-Item -ItemType File (Join-Path $tmp 'archi-plugin') -Force | Out-Null
Remove-Item $dist -ErrorAction SilentlyContinue
& (Tool jar) cfM $dist -C $tmp $jar -C $tmp archi-plugin
Remove-Item -Recurse -Force $tmp

Write-Host "Built: $jar, $dist"
