param([switch]$StartServices, [string]$MavenRepository)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$environmentFile = Join-Path $projectRoot 'deploy/.env'
if (-not (Test-Path -LiteralPath $environmentFile)) { throw '请先创建 deploy/.env，参考 deploy/.env.example。' }
$settings = @{}
Get-Content -LiteralPath $environmentFile | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { $settings[$matches[1]] = $matches[2].Trim() }
}
if (-not $settings['DATABASE_PASSWORD']) { throw '缺少 DATABASE_PASSWORD。' }
$compose = @('compose','--env-file',$environmentFile,'-f',(Join-Path $projectRoot 'deploy/compose.yml'),'-f',(Join-Path $projectRoot 'deploy/compose.local.yml'))
if ($StartServices) {
    & docker @compose up -d --wait mysql qdrant
    if ($LASTEXITCODE -ne 0) { throw '基础服务启动失败。' }
}
$sql = "CREATE DATABASE IF NOT EXISTS camp_agent_it CHARACTER SET utf8mb4; GRANT ALL PRIVILEGES ON camp_agent_it.* TO 'camp_agent'@'%';"
$sql | & docker @compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'
if ($LASTEXITCODE -ne 0) { throw '创建独立集成测试库失败。' }
$variables = @{
    CAMP_TEST_MYSQL_URL = 'jdbc:mysql://127.0.0.1:13316/camp_agent_it?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false'
    CAMP_TEST_MYSQL_USER = 'camp_agent'
    CAMP_TEST_MYSQL_PASSWORD = $settings['DATABASE_PASSWORD']
    CAMP_TEST_QDRANT_URL = 'http://127.0.0.1:16333'
}
$previous = @{}
foreach ($name in $variables.Keys) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
    [Environment]::SetEnvironmentVariable($name,$variables[$name],'Process')
}
Push-Location (Join-Path $projectRoot 'backend')
try {
    $arguments = @('-Pinfrastructure','test')
    if ($MavenRepository) { $arguments += "-Dmaven.repo.local=$MavenRepository" }
    & mvn @arguments
    if ($LASTEXITCODE -ne 0) { throw '集成测试未通过，请查看 backend/target/surefire-reports。' }
} finally {
    Pop-Location
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}

