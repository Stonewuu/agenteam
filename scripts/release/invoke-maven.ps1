param(
    [Parameter(Mandatory = $true)][string]$ProjectDirectory,
    [Parameter(Mandatory = $true)][string]$EncodedArguments
)

$ErrorActionPreference = 'Stop'
$mavenArgumentsJson = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($EncodedArguments))
[string[]]$mavenArguments = ConvertFrom-Json -InputObject $mavenArgumentsJson
Push-Location -LiteralPath $ProjectDirectory
try {
    # 两版共用同一入口；参数作为数组传入，不拼接成命令文本。
    & (Join-Path $ProjectDirectory 'mvnw.cmd') @mavenArguments
    $mavenExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $mavenExitCode
