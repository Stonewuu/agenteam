param(
    [string]$StorageRoot = "D:/agenteam-data/local",
    [switch]$BuildImage
)

$ErrorActionPreference = "Stop"
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
$serverRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "agenteam-server"))
$sourceRoot = [System.IO.Path]::GetFullPath((Join-Path $serverRoot ".agenteam"))
$dataRoot = [System.IO.Path]::GetFullPath($StorageRoot)
if (-not [System.IO.Path]::IsPathRooted($StorageRoot) -or $dataRoot -eq [System.IO.Path]::GetPathRoot($dataRoot) -or $dataRoot -eq $repoRoot -or
    $dataRoot.StartsWith($repoRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "持久目录必须使用工程之外的独立绝对路径，不能使用磁盘根目录"
}
New-Item -ItemType Directory -Path $dataRoot -Force | Out-Null
$recordPath = Join-Path $dataRoot "migration-record.json"
$alreadyMigrated = $false
if (Test-Path -LiteralPath $recordPath) {
    $previous = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    if ($previous.Source -ne $sourceRoot -or $previous.Target -ne $dataRoot) {
        throw "此目录已有其他迁移记录，不能直接复用"
    }
    $alreadyMigrated = $true
}
if ($IsWindows) {
    $accessArguments = @($dataRoot, "/inheritance:r", "/grant:r")
    foreach ($sid in @([System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value, "S-1-5-18", "S-1-5-32-544")) {
        $accessArguments += "*" + $sid + ":(OI)(CI)F"
    }
    & icacls.exe @accessArguments | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "无法设置持久目录的本机访问权限"
    }
}
$manifest = [System.Collections.Generic.List[object]]::new()
foreach ($area in @("files", "security", "states", "workspaces", "workspace-files", "user-workspaces")) {
    $source = Join-Path $sourceRoot $area
    $target = Join-Path $dataRoot $area
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    if ($alreadyMigrated) {
        continue
    }
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        continue
    }
    foreach ($entry in Get-ChildItem -LiteralPath $source -Force -Recurse) {
        if ($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
            throw "原数据包含链接目录或文件，不能自动迁移"
        }
        $relative = [System.IO.Path]::GetRelativePath($sourceRoot, $entry.FullName)
        $destination = [System.IO.Path]::GetFullPath((Join-Path $dataRoot $relative))
        if (-not $destination.StartsWith($dataRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "迁移目标超出持久目录"
        }
        if ($entry.PSIsContainer) {
            New-Item -ItemType Directory -Path $destination -Force | Out-Null
            continue
        }
        $hash = (Get-FileHash -LiteralPath $entry.FullName -Algorithm SHA256).Hash
        if (Test-Path -LiteralPath $destination) {
            if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $hash) {
                throw "目标目录存在不同内容，未覆盖任何文件；请使用空目录或核对迁移状态"
            }
        } else {
            [System.IO.File]::Copy($entry.FullName, $destination, $false)
        }
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $hash) {
            throw "复制后的文件摘要不一致，未切换配置"
        }
        $manifest.Add([pscustomobject]@{ Source = $entry.FullName; Target = $destination; Sha256 = $hash })
    }
}
foreach ($entry in $manifest) {
    if ((Get-FileHash -LiteralPath $entry.Source -Algorithm SHA256).Hash -ne $entry.Sha256) {
        throw "原数据在复制期间发生变化，未切换配置；请停止后端写入后重新准备目录"
    }
}
if ($BuildImage) {
    docker build --tag agenteam/sandbox-office:local (Join-Path $repoRoot "deploy/sandbox")
    if ($LASTEXITCODE -ne 0) {
        throw "办公镜像构建失败，未切换配置"
    }
}
$configPath = Join-Path $sourceRoot "local.properties"
New-Item -ItemType Directory -Path $sourceRoot -Force | Out-Null
$contents = if (Test-Path -LiteralPath $configPath) {
    [System.IO.File]::ReadAllText($configPath)
} else {
    "# 本机持久目录，保留原始数据副本；修改后重启 IDEA 中的后端。`n"
}
$properties = [ordered]@{
    "agenteam.storage.root" = '${AGENTEAM_STORAGE_ROOT:' + $dataRoot.Replace("\", "/") + '}'
    "files.storage" = '${AGENTEAM_FILE_STORAGE:auto}'
    "execution.sandbox.image" = '${AGENTEAM_SANDBOX_IMAGE:agenteam/sandbox-office:local}'
    "execution.sandbox.workspace-mb" = '${AGENTEAM_SANDBOX_WORKSPACE_MB:512}'
    "execution.sandbox.memory-mb" = '${AGENTEAM_SANDBOX_MEMORY_MB:2048}'
    "execution.sandbox.temporary-mb" = '${AGENTEAM_SANDBOX_TEMPORARY_MB:256}'
    "execution.sandbox.maximum-concurrent" = '${AGENTEAM_SANDBOX_MAXIMUM_CONCURRENT:2}'
}
foreach ($key in $properties.Keys) {
    $pattern = "(?m)^" + [regex]::Escape($key) + "\s*=.*$"
    $line = $key + "=" + $properties[$key]
    if ([regex]::IsMatch($contents, $pattern)) {
        $contents = [regex]::Replace($contents, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($match) $line })
    } else {
        $contents = $contents.TrimEnd() + "`n" + $line + "`n"
    }
}
[System.IO.File]::WriteAllText($configPath, $contents, [System.Text.UTF8Encoding]::new($false))
if (-not $alreadyMigrated) {
    $record = [pscustomobject]@{ CopiedAt = [DateTimeOffset]::Now.ToString("o"); Source = $sourceRoot; Target = $dataRoot; FileCount = $manifest.Count }
    [System.IO.File]::WriteAllText($recordPath, ($record | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
}
Write-Output "已准备持久目录：$dataRoot"
if ($alreadyMigrated) {
    Write-Output "沿用已有迁移结果，没有用旧副本覆盖新文件。重启 IDEA 后端后使用新目录。"
} else {
    Write-Output "已校验 $($manifest.Count) 个文件，原始数据仍保留。重启 IDEA 后端后使用新目录。"
}
