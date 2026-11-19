param(
    [ValidateSet('single', 'split')]
    [string]$Mode = 'single',
    [string]$EnvFile = (Join-Path $PSScriptRoot '.env')
)

$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $EnvFile) {
    throw '配置文件已经存在，请直接编辑它；初始化脚本不会覆盖已有密码。'
}

$configuration = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../.env.example') -Raw -Encoding UTF8
$publicUrl = 'http://localhost:8088'
$composeFiles = '../docker-compose.yaml,compose/source.yaml'
if ($Mode -eq 'split') {
    $publicUrl = 'http://localhost:3000'
    $composeFiles += ',compose/split.yaml'
}
$configuration = [regex]::Replace($configuration, '(?m)^PUBLIC_URL=\r?$', "PUBLIC_URL=$publicUrl")
$configuration = $configuration.TrimEnd() + "`nDEPLOY_MODE=$Mode`nCOMPOSE_FILE=$composeFiles`n"

$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    foreach ($key in @('DB_PASSWORD', 'DB_ROOT_PASSWORD', 'REDIS_PASSWORD', 'SANDBOX_TOKEN', 'SETUP_CREDENTIAL')) {
        $bytes = New-Object byte[] 32
        $random.GetBytes($bytes)
        $value = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
        $configuration = [regex]::Replace($configuration, '(?m)^' + $key + '=\r?$', $key + '=' + $value)
    }
} finally {
    $random.Dispose()
}

$path = [System.IO.Path]::GetFullPath($EnvFile)
$stream = [System.IO.File]::Open($path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
try {
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($configuration)
    $stream.Write($bytes, 0, $bytes.Length)
} finally {
    $stream.Dispose()
}
Write-Host "已生成配置：$path"
Write-Host '请查看最上方的端口与访问地址，然后在 deploy 目录执行 docker compose up -d --build。'
Write-Host '初始化页面所需凭据为配置文件中的 SETUP_CREDENTIAL，不会输出到终端。'
