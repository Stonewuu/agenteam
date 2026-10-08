param(
    [ValidateSet('single', 'split')]
    [string]$Mode = 'single',
    [string]$EnvFile = (Join-Path $PSScriptRoot '.env')
)

$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $EnvFile) {
    throw '配置文件已经存在，请直接编辑它；初始化脚本不会覆盖已有密码。'
}

$configuration = Get-Content -LiteralPath (Join-Path $PSScriptRoot '.env.example') -Raw -Encoding UTF8
$configuration = $configuration.Replace('DEPLOY_MODE=single', 'DEPLOY_MODE=' + $Mode)
if ($Mode -eq 'split') {
    $configuration = $configuration.Replace('PUBLIC_URL=http://localhost:8088', 'PUBLIC_URL=http://localhost:3000')
}

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
