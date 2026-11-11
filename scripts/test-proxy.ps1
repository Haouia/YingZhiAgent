param([string]$BaseUrl='http://127.0.0.1:18090')
$ErrorActionPreference='Stop'
$rows=@()
foreach($number in 1..8){
    $response=Invoke-WebRequest "$($BaseUrl.TrimEnd('/'))/api/auth/login" -Method Post -ContentType 'application/json' -Body '{"username":"proxy-check-nonexistent","password":"invalid-proxy-check"}' -SkipHttpErrorCheck -TimeoutSec 10
    if($response.StatusCode -notin @(401,429)){throw "代理检查返回异常状态：$($response.StatusCode)"}
    if($response.StatusCode -eq 429){
        $body=$response.Content|ConvertFrom-Json
        if(-not $body.message -or [int]$response.Headers['Retry-After'][0] -lt 1){throw '限流必须返回 JSON 提示和 Retry-After'}
    }
    $rows+=@{request=$number;status=[int]$response.StatusCode}
}
if(@($rows|Where-Object status -eq 429).Count -eq 0){throw '没有触发代理限流，请核对当前入口配置'}
$rows|ConvertTo-Json
