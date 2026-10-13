param([switch]$BootstrapAdmin, [switch]$ConfigureAi)
$ErrorActionPreference = 'Stop'
function Read-Secret([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}
if (-not $env:MYSQL_PASSWORD) {
    $env:MYSQL_PASSWORD = Read-Secret '本地 MySQL root 密码'
}
if (-not $env:VECTOR_DB_PASSWORD) {
    # 从本项目容器读取初始化密码，仅置于子进程环境，不显示或写入文件。
    $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
    if ($dockerCommand) {
        try {
            $containerJson = & docker inspect hospital-triage-pg 2>$null
            if ($LASTEXITCODE -eq 0) {
                $container = @($containerJson | ConvertFrom-Json)[0]
                $entry = $container.Config.Env | Where-Object { $_.StartsWith('POSTGRES_PASSWORD=') } | Select-Object -First 1
                if ($entry) { $env:VECTOR_DB_PASSWORD = $entry.Substring('POSTGRES_PASSWORD='.Length) }
            }
        } catch {
            Write-Host '无法自动读取本项目容器配置，请输入数据库连接密码。'
        }
    }
    if (-not $env:VECTOR_DB_PASSWORD) {
        $env:VECTOR_DB_PASSWORD = Read-Secret 'PostgreSQL hospital_vector_user 密码'
    }
}
if (-not $env:JWT_SECRET) {
    $bytes = New-Object byte[] 48
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $env:JWT_SECRET = [Convert]::ToBase64String($bytes)
    Write-Host '已生成本次会话的 JWT 签名密钥；下次启动会要求重新登录。'
}
if ($BootstrapAdmin) {
    $env:BOOTSTRAP_ADMIN_ENABLED = 'true'
    if (-not $env:BOOTSTRAP_ADMIN_PASSWORD) {
        $env:BOOTSTRAP_ADMIN_PASSWORD = Read-Secret '首次管理员密码（至少8个字符）'
    }
}
if ($ConfigureAi -and -not $env:DASHSCOPE_API_KEY) {
    $env:DASHSCOPE_API_KEY = Read-Secret '百炼 API Key（仅用于当前进程，不写入文件）'
}
$serverPath = Split-Path -Parent $PSScriptRoot
Push-Location $serverPath
try {
    & mvn -B -ntp package '-DskipTests'
    if ($LASTEXITCODE -ne 0) { throw '构建失败' }
    & java -jar target/hospital-server-0.1.0-SNAPSHOT.jar
    if ($LASTEXITCODE -ne 0) { throw '后端启动失败' }
} finally {
    Pop-Location
}
