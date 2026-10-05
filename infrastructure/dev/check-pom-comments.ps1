<#
.SYNOPSIS
    Fails if any pom.xml contains '--' inside an XML comment.

.DESCRIPTION
    XML forbids '--' within a comment. Maven's parse error for it names the position in the
    file but not the cause, and it takes the ENTIRE reactor down, so every module becomes
    unbuildable for what looks like a one-line typo. Worth a check of its own.
#>
param()

$failures = @()
Get-ChildItem -Path (Join-Path $PSScriptRoot '..\..') -Recurse -Filter 'pom.xml' -File |
    Where-Object { $_.FullName -notmatch '[\\/]target[\\/]|[\\/]backend[\\/]|[\\/]node_modules[\\/]' } |
    ForEach-Object {
        $inComment = $false
        $lineNumber = 0
        foreach ($line in [System.IO.File]::ReadAllLines($_.FullName)) {
            $lineNumber++
            if ($line -match '<!--') { $inComment = $true }
            if ($inComment -and $line -notmatch '<!--' -and $line -notmatch '-->' -and $line -match '--') {
                $failures += [pscustomobject]@{
                    File = $_.FullName
                    Line = $lineNumber
                    Text = $line.Trim()
                }
            }
            if ($line -match '-->') { $inComment = $false }
        }
    }

if ($failures.Count -gt 0) {
    $failures | Format-Table -AutoSize
    Write-Error "'--' is not legal inside an XML comment. This breaks the whole reactor, not just the module."
    exit 1
}

Write-Host 'OK: no '--' inside any XML comment.'
exit 0