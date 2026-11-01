param(
    [Parameter(Mandatory, Position=0)]
    [ValidateSet('status','get','list','set','remove','apply','export')][string]$Command,
    [Parameter(Position=1)][ValidatePattern('^[A-Za-z0-9_.:-]*$')][string]$Key,
    [Parameter(Position=2)][AllowEmptyString()][string]$Value,
    [ValidateSet('boolean','int','long','float','string','string_set')][string]$Type,
    [string]$File,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }
$method = if ($Command -eq 'export') { 'list' } else { $Command }
$remote = "content call --uri content://com.sevtinge.hyperceiler.adb --method $method"
if ($Key) { $remote += " --arg $Key" }
if ($Command -in @('get','set','remove') -and !$Key) { throw 'This command requires a key.' }
if ($Command -eq 'set') {
    if (!$PSBoundParameters.ContainsKey('Value')) { throw 'set requires a value; use remove to delete a key.' }
    # Only Base64 crosses the remote shell, including quotes, Unicode and newlines.
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
    if ($encoded.Length -gt 90000) { throw 'Encoded value exceeds the 90000-character transport limit.' }
    if ($encoded) { $remote += " --extra value64:s:$encoded" }
    else { $remote += " --extra 'value:s:'" }
    if ($Type) { $remote += " --extra type:s:$Type" }
}
if ($Command -eq 'apply') {
    if (!$File) { throw 'apply requires -File with a JSON object of preference changes.' }
    $json = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $File).Path)
    if ($json.Length -gt 65536) { throw 'JSON exceeds the 65536-character limit.' }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
    if ($encoded.Length -gt 90000) { throw 'Encoded JSON exceeds the 90000-character transport limit.' }
    $remote += " --extra payload:s:$encoded"
}
$output = (& adb @adbArgs shell $remote 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw $output }
$match = [regex]::Match($output, '\bdata=([A-Za-z0-9+/=]+)')
if (!$match.Success) { throw "No response data: $output" }
$decoded = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($match.Groups[1].Value))
$result = $decoded | ConvertFrom-Json
if ($output -match '\bok=false\b') { throw "Preference operation failed: $decoded" }
if ($Command -eq 'export') {
    if (!$File) { throw 'export requires -File.' }
    $backup = [ordered]@{}
    foreach ($entry in $result.entries.PSObject.Properties) {
        if ($entry.Name -in @('prefs_key_allow_hook','prefs_key_framework_api_allow_hook') -or
            $entry.Name.StartsWith('prefs_key_framework_check_')) { continue }
        $backup[$entry.Name] = $entry.Value
    }
    $entries = ConvertTo-Json -InputObject $backup -Depth 20
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($File), $entries, [Text.UTF8Encoding]::new($false))
    Write-Output "Exported to $File"
} else {
    Write-Output $decoded
}
