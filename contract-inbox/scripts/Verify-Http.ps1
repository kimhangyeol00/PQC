#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Java = 'java',
    [string]$Jar = (Join-Path $PSScriptRoot '../target/application.jar'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../target/http-verification')
)
$ErrorActionPreference = 'Stop'
$Jar = (Resolve-Path -LiteralPath $Jar).Path
$Java = (Get-Command $Java -ErrorAction Stop).Source
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$runDirectory = Join-Path $OutputDirectory ([guid]::NewGuid().ToString('N'))
$dataDirectory = Join-Path $runDirectory 'data'
[IO.Directory]::CreateDirectory($runDirectory) | Out-Null
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$base = "http://127.0.0.1:$port"
$script:appProcess = $null

function Start-App([int]$Run) {
    $arguments = @('-jar', ('"{0}"' -f $Jar), ('"--workspace.directory={0}"' -f $dataDirectory),
        '--workspace.seed=false', "--server.port=$port", '--server.address=127.0.0.1')
    $startOptions = @{
        FilePath = $Java; ArgumentList = $arguments; PassThru = $true
        RedirectStandardOutput = (Join-Path $runDirectory "stdout-$Run.log")
        RedirectStandardError = (Join-Path $runDirectory "stderr-$Run.log")
    }
    if ($IsWindows) { $startOptions.WindowStyle = 'Hidden' }
    $script:appProcess = Start-Process @startOptions
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        if ($script:appProcess.HasExited) { throw "Application exited: $($script:appProcess.ExitCode)" }
        try {
            if ((Invoke-RestMethod "$base/health" -TimeoutSec 2).status -eq 'ok') { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw 'Application did not become healthy'
}
function Stop-App {
    if ($null -ne $script:appProcess) {
        if (!$script:appProcess.HasExited) { $script:appProcess.Kill(); $script:appProcess.WaitForExit() }
        $script:appProcess.Dispose(); $script:appProcess = $null
    }
}
function Request-Api([string]$Method, [string]$Path, [string]$User, $Body, [int]$Expected) {
    $options = @{ Uri = "$base$Path"; Method = $Method; SkipHttpErrorCheck = $true; TimeoutSec = 20 }
    if ($User) { $options.Headers = @{ 'X-User' = $User } }
    if ($null -ne $Body) {
        $options.ContentType = 'application/json; charset=utf-8'
        $options.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress))
    }
    $response = Invoke-WebRequest @options
    if ([int]$response.StatusCode -ne $Expected) { throw "$Method $Path expected $Expected, got $($response.StatusCode)" }
    return ($response.Content | ConvertFrom-Json)
}
try {
    Start-App 1
    $content = "최종 제출 검증 / 한글, 따옴표 `"계약`", 줄바꿈`n보존 본문"
    $created = Request-Api POST '/contracts' 'alice' @{recipient='bob';title='제출 검증';content=$content} 201
    $id = $created.contract_id
    $null = Request-Api GET "/contracts/$id" 'dave' $null 404
    $null = Request-Api GET "/contracts/$id" 'bob' $null 404
    $null = Request-Api GET '/contracts' '' $null 401
    $null = Request-Api POST "/contracts/$id/deliveries" 'alice' @{version=1} 201
    $null = Request-Api POST "/contracts/$id/comments" 'bob' @{version=1;content='{JSON이 아닌 정상 의견'} 201
    $null = Request-Api POST "/contracts/$id/approvals" 'alice' @{version=1} 403
    $null = Request-Api POST "/contracts/$id/approvals" 'bob' @{version=1} 201
    $before = Request-Api GET "/contracts/$id/approved-copy" 'alice' $null 200
    if (!$before.signature_valid -or $before.content -cne $content -or $before.approval.scheme -ne 'ML-DSA-65') { throw 'Approval verification failed' }
    $publicKey = (Request-Api GET '/users/bob/public-keys' 'alice' $null 200).encryption.key
    Stop-App
    Start-App 2
    $after = Request-Api GET "/contracts/$id/approved-copy" 'bob' $null 200
    if (!$after.signature_valid -or $after.content -cne $content) { throw 'Restart persistence failed' }
    if ((Request-Api GET '/users/bob/public-keys' 'alice' $null 200).encryption.key -cne $publicKey) { throw 'Public key changed across restart' }
    $comments = @(Request-Api GET "/contracts/$id/comments?version=1" 'alice' $null 200)
    if ($comments.Count -ne 1 -or $comments[0].content -cne '{JSON이 아닌 정상 의견') { throw 'Comment persistence failed' }
    $result = [ordered]@{
        status='passed'; scheme=$after.approval.scheme; envelope=$after.envelope.format
        process_restart=$true; same_public_key=$true; content_equal=$true; comment_equal=$true
        unauthorized=404; before_delivery=404; missing_identity=401; author_approval=403
        jar_sha256=(Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash
    }
    $json = $result | ConvertTo-Json
    $json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'result.json') -Encoding utf8
    $json
} finally { Stop-App }
