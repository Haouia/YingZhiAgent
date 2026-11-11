$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$environmentFile = Join-Path $projectRoot 'deploy/.env'
if (-not (Test-Path -LiteralPath $environmentFile)) { throw '请先按 deploy/.env.example 配置 deploy/.env。' }
$settings = @{}
Get-Content -LiteralPath $environmentFile | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { $settings[$matches[1]] = $matches[2].Trim() }
}
$provider = $settings['CHAT_PROVIDER']
if (-not $provider) { $provider = if ($settings['DEEPSEEK_API_KEY']) { 'deepseek' } else { 'dashscope' } }
if ($provider -notin @('deepseek','dashscope')) { throw 'CHAT_PROVIDER 只支持 deepseek 或 dashscope。' }
$providerKey = if ($provider -eq 'deepseek') { 'DEEPSEEK_API_KEY' } else { 'DASHSCOPE_API_KEY' }
$retrieval = $settings['RETRIEVAL_MODE']
if (-not $retrieval) { $retrieval = if ($provider -eq 'deepseek') { 'keyword' } else { 'hybrid' } }
if ($retrieval -notin @('keyword','hybrid')) { throw 'RETRIEVAL_MODE 只支持 keyword 或 hybrid。' }
foreach ($required in @($providerKey,'DATABASE_PASSWORD','JWT_SECRET','DEMO_PASSWORD')) {
    if (-not $settings[$required]) { throw "请在 deploy/.env 中配置 $required，不要将密钥发送到聊天或提交 Git。" }
}
if ($retrieval -eq 'hybrid' -and -not $settings['DASHSCOPE_API_KEY']) { throw 'hybrid 检索还需要独立的 DASHSCOPE_API_KEY，用于向量化和重排序。' }
if (Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue) { throw '8081 已占用。请先停止当前本地 demo 后端，再切换 cloud；前端可保留。' }
$amapKey = (([string]$settings['AMAP_WEB_SERVICE_KEY']) -replace '\s+#.*$', '').Trim().Trim('"', "'")
$variables = @{
    AGENT_MODE = 'cloud'
    PORT = '8081'
    BIND_ADDRESS = '127.0.0.1'
    STORAGE_PATH = (Join-Path $projectRoot 'backend/.local/cloud-documents')
    SPRING_PROFILES_ACTIVE = 'cloud-local'
    DATABASE_URL = 'jdbc:mysql://127.0.0.1:13316/camp_agent?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false'
    DATABASE_USER = 'camp_agent'
    DATABASE_PASSWORD = $settings['DATABASE_PASSWORD']
    JWT_SECRET = $settings['JWT_SECRET']
    DEMO_PASSWORD = $settings['DEMO_PASSWORD']
    DASHSCOPE_API_KEY = $settings['DASHSCOPE_API_KEY']
    DEEPSEEK_API_KEY = $settings['DEEPSEEK_API_KEY']
    CHAT_PROVIDER = $provider
    RETRIEVAL_MODE = $retrieval
    AMAP_WEB_SERVICE_KEY = $amapKey
    AMAP_MCP_ENABLED = $(if ($settings.ContainsKey('AMAP_MCP_ENABLED')) { $settings['AMAP_MCP_ENABLED'] } elseif ($amapKey) { 'true' } else { 'false' })
    DEEPSEEK_MODEL = $(if ($settings['DEEPSEEK_MODEL']) { $settings['DEEPSEEK_MODEL'] } else { 'deepseek-flash' })
    QDRANT_URL = 'http://127.0.0.1:16333'
}
foreach ($name in @('MODEL_BASE_URL','RERANK_URL')) {
    if ($settings[$name]) { $variables[$name] = $settings[$name] }
}
$previous = @{}
foreach ($name in $variables.Keys) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
    [Environment]::SetEnvironmentVariable($name,$variables[$name],'Process')
}
Push-Location (Join-Path $projectRoot 'backend')
try {
    Write-Host "启动 cloud：对话服务 $provider，知识检索 $retrieval。密钥不会输出。"
    & java -jar target/camp-agent-0.1.0.jar
    if ($LASTEXITCODE -ne 0) { throw 'cloud 后端退出，请查看上方启动信息。' }
} finally {
    Pop-Location
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}

