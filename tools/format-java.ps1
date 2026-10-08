param([switch]$Check, [string]$RepositoryPath)

$ErrorActionPreference = "Stop"
$repository = if ($RepositoryPath) {
    (Resolve-Path -LiteralPath $RepositoryPath).Path
} else {
    [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
}
if (-not (Test-Path -LiteralPath (Join-Path $repository ".git"))) {
    throw "Java 格式检查的目标必须是代码仓库根目录"
}
$cache = Join-Path $repository "target/format-tools"
New-Item -ItemType Directory -Path $cache -Force | Out-Null
$parser = Join-Path $cache "javaparser-core-3.27.1.jar"
$expected = "7ae10d11c5388060a7bf93b721600b951f5585f3782778b6d0555160680d35f8"
if (-not (Test-Path -LiteralPath $parser)) {
    Invoke-WebRequest "https://repo.maven.apache.org/maven2/com/github/javaparser/javaparser-core/3.27.1/javaparser-core-3.27.1.jar" -OutFile $parser
}
if ((Get-FileHash -LiteralPath $parser -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw "Java 语法分析工具的文件摘要不一致"
}
$files = @(git -C $repository ls-files --cached --others --exclude-standard -- "*.java" | Sort-Object -Unique | Where-Object {
    Test-Path -LiteralPath (Join-Path $repository $_) -PathType Leaf
})
$manifest = Join-Path $cache "java-files.txt"
[System.IO.File]::WriteAllLines($manifest, $files, [System.Text.UTF8Encoding]::new($false))
$mode = if ($Check) { "--check" } else { "--write" }
java -Xmx1g -cp $parser (Join-Path $PSScriptRoot "format/JavaCodeFormat.java") $mode $repository $manifest
exit $LASTEXITCODE
