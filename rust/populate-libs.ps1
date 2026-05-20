# populate-libs.ps1
# Run from the rust/ workspace root after `cargo build`.
# Copies the built DLLs into itara-libs/ alongside the .itara metadata files.
# The .itara files are already in itara-libs/ and do not need to be copied.

$TargetDir = ".\target\debug"
$LibDir    = ".\itara-libs"

$Copies = @(
    @{ Src = "calculator_component.dll"; Dst = "calculator_component.dll" },
    @{ Src = "calculator_api.dll";       Dst = "calculator_api.dll"       },
    @{ Src = "gateway_component.dll";    Dst = "gateway_component.dll"    },
    @{ Src = "gateway_api.dll";          Dst = "gateway_api.dll"          },
    @{ Src = "itara_transport_http.dll"; Dst = "itara_transport_http.dll" }
)

foreach ($copy in $Copies) {
    $src = Join-Path $TargetDir $copy.Src
    $dst = Join-Path $LibDir    $copy.Dst
    if (Test-Path $src) {
        Copy-Item -Path $src -Destination $dst -Force
        Write-Host "Copied $($copy.Src) -> itara-libs\"
    } else {
        Write-Warning "Not found (skipping): $src"
    }
}

Write-Host "`nLib dir contents:"
Get-ChildItem $LibDir | Format-Table Name, LastWriteTime -AutoSize
