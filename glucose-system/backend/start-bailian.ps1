[CmdletBinding()]
param(
    [string]$BaseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    [string]$ChatModel = 'qwen-plus',
    [string]$EmbeddingModel = 'text-embedding-v3',
    [int]$EmbeddingDim = 1024,
    [int]$Port = 8083
)
$ErrorActionPreference = 'Stop'
# Only provider settings are changed. Database/Redis use the existing local configuration.
$taskNames = @('LLM_BASE_URL','LLM_API_KEY','LLM_MODEL','EMBEDDING_BASE_URL','EMBEDDING_API_KEY','EMBEDDING_MODEL','EMBEDDING_DIM')
$taskPrevious = @{}
foreach ($taskName in $taskNames) { $taskPrevious[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process') }
$taskKey = $env:BAILIAN_API_KEY
$taskSecureKey = $null
Push-Location -LiteralPath $PSScriptRoot
try {
    & mvn -B -ntp package
    if ($LASTEXITCODE -ne 0) { throw '构建未通过，未启动后端。' }
    if (!$taskKey) {
        $taskSecureKey = Read-Host '请输入阿里百炼 API Key（输入不会显示，也不保存到文件）' -AsSecureString
        $taskKeyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecureKey)
        try { $taskKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskKeyPointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskKeyPointer) }
    }
    if ([string]::IsNullOrWhiteSpace($taskKey)) { throw 'API Key 不能为空。' }
    $env:LLM_BASE_URL = $BaseUrl
    $env:LLM_MODEL = $ChatModel
    $env:LLM_API_KEY = $taskKey
    $env:EMBEDDING_BASE_URL = $BaseUrl
    $env:EMBEDDING_MODEL = $EmbeddingModel
    $env:EMBEDDING_API_KEY = $taskKey
    $env:EMBEDDING_DIM = [string]$EmbeddingDim
    & java -jar target/glucose-backend.jar --spring.profiles.active=dev "--server.port=$Port" --logging.level.com.glucose=INFO
    if ($LASTEXITCODE -ne 0) { throw '后端启动失败，请检查本地端口及数据库配置。' }
} finally {
    foreach ($taskName in $taskNames) { [Environment]::SetEnvironmentVariable($taskName, $taskPrevious[$taskName], 'Process') }
    $taskKey = $null
    if ($taskSecureKey) { $taskSecureKey.Dispose() }
    Pop-Location
}
