param([switch]$Live)

$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$jar = Join-Path $project 'backend/target/camp-agent-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw '请先打包后端。' }
$run = New-Item -ItemType Directory -Path (Join-Path $project ('.local/keyword-eval-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')))
$results = Join-Path $run.FullName 'results.jsonl'
$cases = Join-Path $project 'data/evaluation/keyword-baseline.jsonl'
$jarHash = (Get-FileHash -LiteralPath $jar).Hash
$casesHash = (Get-FileHash -LiteralPath $cases).Hash
@{ jarSha256 = $jarHash; casesSha256 = $casesHash } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run.FullName 'inputs.json')
$runtime = New-Item -ItemType Directory -Path (Join-Path $run.FullName 'runtime')
$previousKey = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Process')
$model = 'deepseek-flash'
try {
    if ($Live) {
        $config = @{}
        Get-Content -LiteralPath (Join-Path $project 'deploy/.env') | ForEach-Object {
            if ($_ -match '^(DEEPSEEK_API_KEY|DEEPSEEK_MODEL)=(.*)$') { $config[$matches[1]] = $matches[2].Trim() }
        }
        if (-not $config['DEEPSEEK_API_KEY']) { throw '真实模型评测需要 deploy/.env 中的 DEEPSEEK_API_KEY。' }
        $env:DEEPSEEK_API_KEY = $config['DEEPSEEK_API_KEY']
        if ($config['DEEPSEEK_MODEL']) { $model = $config['DEEPSEEK_MODEL'] }
    }
    Push-Location $runtime.FullName
    try {
        & jar --extract --file $jar BOOT-INF/classes BOOT-INF/lib
        if ($LASTEXITCODE -ne 0) { throw '解包运行依赖失败。' }
        & java '-Dfile.encoding=UTF-8' '-cp' 'BOOT-INF/classes;BOOT-INF/lib/*' (Join-Path $PSScriptRoot 'KeywordEvaluation.java') $cases $results $Live.IsPresent.ToString() $model
        if ($LASTEXITCODE -ne 0) { throw '评测进程异常退出。' }
    } finally {
        Pop-Location
    }
    Write-Output "结果文件 $results"
} finally {
    [Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY', $previousKey, 'Process')
    $resolved = (Resolve-Path -LiteralPath $runtime.FullName).ProviderPath
    if ($resolved -eq (Join-Path $run.FullName 'runtime') -and
        $run.FullName.StartsWith((Join-Path $project '.local/keyword-eval-'), [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
