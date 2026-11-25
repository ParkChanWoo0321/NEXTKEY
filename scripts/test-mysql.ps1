# Runs the suite on a NEW private MySQL instance, followed by packaged dev/prod checks.
[CmdletBinding()]
param (
    [string]$MysqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin',
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$server = Join-Path $MysqlBin 'mysqld.exe'
$client = Join-Path $MysqlBin 'mysql.exe'
if (!(Test-Path -LiteralPath $server) -or !(Test-Path -LiteralPath $client)) { throw 'Specify -MysqlBin with MySQL Server 8 binaries.' }
$workRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$wrapper = Join-Path $workRoot 'gradlew.bat'
$summaryPath = Join-Path $workRoot 'build/reports/mysql-verification.json'
if (Test-Path -LiteralPath $summaryPath) { Remove-Item -LiteralPath $summaryPath }
$tempRoot = [IO.Path]::GetTempPath()
# Some Windows MySQL 8.0 builds truncate non-ASCII command-line paths.
if ($tempRoot -match '[^\x00-\x7f]') { $tempRoot = Join-Path $env:WINDIR 'Temp' }
$directory = Join-Path $tempRoot ('nextkey-test-' + [guid]::NewGuid().ToString('N'))
$data = Join-Path $directory 'data'
$init = Join-Path $directory 'init.sql'
New-Item -ItemType Directory -Path $directory | Out-Null
$acl = Get-Acl -LiteralPath $directory
$acl.SetAccessRuleProtection($true,$false)
$rule = New-Object Security.AccessControl.FileSystemAccessRule(
    [Security.Principal.WindowsIdentity]::GetCurrent().User, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
$acl.SetAccessRule($rule)
Set-Acl -LiteralPath $directory -AclObject $acl
$daemon = $null
$original = @{}
$variables = @('JAVA_HOME','MYSQL_PWD','NEXTKEY_TEST_MYSQL','NEXTKEY_SPEC_AUDIT','NEXTKEY_DB_URL','NEXTKEY_DB_USER',
    'NEXTKEY_DB_PASSWORD','NEXTKEY_DEV_PASSWORD','NEXTKEY_DEV_OTP','NEXTKEY_PDF_FONT',
    'NEXTKEY_DEV_SIMPLE_CUSTOMER_LOGIN','NEXTKEY_DEV_SIMPLE_CUSTOMER_PASSWORD','NEXTKEY_DEV_REVIEW_HANDOFF',
    'NEXTKEY_RESERVATIONS_SOURCE','SPRING_PROFILES_ACTIVE','SPRING_APPLICATION_JSON',
    'SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD',
    'SPRING_FLYWAY_URL','SPRING_FLYWAY_USER','SPRING_FLYWAY_PASSWORD','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS')
foreach ($name in $variables) { $original[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    # Keep caller DB/profile/JVM overrides away from the private test process. Restore them below.
    $env:SPRING_APPLICATION_JSON = $null
    $env:JAVA_TOOL_OPTIONS = $null
    $env:JDK_JAVA_OPTIONS = $null
    $env:NEXTKEY_RESERVATIONS_SOURCE = $null
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:NEXTKEY_DEV_SIMPLE_CUSTOMER_LOGIN = 'false'
    $env:NEXTKEY_DEV_REVIEW_HANDOFF = 'false'
    $env:NEXTKEY_DB_PASSWORD = [guid]::NewGuid().ToString('N')
    $env:NEXTKEY_DEV_PASSWORD = [guid]::NewGuid().ToString('N')
    $env:NEXTKEY_DEV_OTP = (Get-Random -Minimum 100000 -Maximum 1000000).ToString()
    $env:NEXTKEY_DB_USER = 'nextkey_test'
    $env:SPRING_DATASOURCE_USERNAME = $env:NEXTKEY_DB_USER
    $env:SPRING_DATASOURCE_PASSWORD = $env:NEXTKEY_DB_PASSWORD
    $env:SPRING_FLYWAY_USER = $env:NEXTKEY_DB_USER
    $env:SPRING_FLYWAY_PASSWORD = $env:NEXTKEY_DB_PASSWORD
    $env:NEXTKEY_TEST_MYSQL = 'true'
    $env:NEXTKEY_SPEC_AUDIT = 'true'
    if (!$env:NEXTKEY_PDF_FONT -and (Test-Path "$env:WINDIR\Fonts\malgun.ttf")) {
        $env:NEXTKEY_PDF_FONT = "$env:WINDIR\Fonts\malgun.ttf"
    }
    # Root has its own random password. The application account is scoped to the two fixture schemas.
    $rootPassword = [guid]::NewGuid().ToString('N')
    $lines = @("ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';",
        "CREATE DATABASE nextkey_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
        "CREATE DATABASE nextkey_prod_check CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;",
        "CREATE USER 'nextkey_test'@'127.0.0.1' IDENTIFIED BY '$env:NEXTKEY_DB_PASSWORD';",
        "GRANT ALL ON nextkey_test.* TO 'nextkey_test'@'127.0.0.1';",
        "GRANT ALL ON nextkey_prod_check.* TO 'nextkey_test'@'127.0.0.1';",
        "CREATE USER 'nextkey_stop'@'127.0.0.1' IDENTIFIED BY '$rootPassword';",
        "GRANT SHUTDOWN ON *.* TO 'nextkey_stop'@'127.0.0.1';")
    [IO.File]::WriteAllLines($init, $lines, [Text.Encoding]::ASCII)
    $portProbe = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $portProbe.Start()
    $port = $portProbe.LocalEndpoint.Port
    $portProbe.Stop()
    & $server --no-defaults --initialize-insecure "--datadir=$data" "--log-error=$directory/init.log"
    if ($LASTEXITCODE -ne 0) { throw "Private MySQL initialization failed. See $directory/init.log" }
    $argsList = @('--no-defaults', '--no-monitor', ('"--datadir={0}"' -f $data), ('"--init-file={0}"' -f $init),
        '--console', '--bind-address=127.0.0.1', "--port=$port",
        '--mysqlx=OFF', '--skip-name-resolve', '--default-time-zone=+00:00')
    $daemon = Start-Process -FilePath $server -ArgumentList $argsList -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $directory 'server.log')
    $env:MYSQL_PWD = $env:NEXTKEY_DB_PASSWORD
    $env:NEXTKEY_DB_URL = "jdbc:mysql://127.0.0.1:$port/nextkey_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&sslMode=DISABLED"
    $env:SPRING_DATASOURCE_URL = $env:NEXTKEY_DB_URL
    $env:SPRING_FLYWAY_URL = $env:NEXTKEY_DB_URL
    $online = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($daemon.HasExited) { throw "Private MySQL stopped during startup (exit $($daemon.ExitCode))." }
        # Probe TCP first so shells that promote native stderr to errors don't abort during startup.
        $socket = New-Object Net.Sockets.TcpClient
        try { $socket.Connect('127.0.0.1', $port) } catch {} finally { $socket.Dispose() }
        try {
            $probe = & $client --no-defaults --connect-timeout=2 --protocol=TCP '--host=127.0.0.1' "--port=$port" --user=nextkey_test --database=nextkey_test -N -B -e 'SELECT VERSION()' 2>$null
            if ($LASTEXITCODE -eq 0) { Write-Host "Isolated MySQL $probe is ready."; $online = $true; break }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    if (!$online) { throw "Private MySQL did not become ready. See $directory/server.log" }
    Remove-Item -LiteralPath $init
    $env:MYSQL_PWD = $null
    Push-Location $workRoot
    try {
        $gradleArgs = @('test','bootJar','--rerun-tasks','--console=plain')
        if ($Offline) { $gradleArgs += '--offline' }
        & $wrapper @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw 'Gradle tests failed. See build/reports/tests/test/index.html.' }
        $suites = @(Get-ChildItem -Path 'build/test-results/test/TEST-*.xml' | ForEach-Object {
            ([xml](Get-Content -Raw -LiteralPath $_.FullName)).testsuite
        })
        if (!$suites.Count) { throw 'No JUnit XML results were produced.' }
        $testCount = 0
        foreach ($suite in $suites) {
            if ([int]$suite.skipped -or [int]$suite.errors -or [int]$suite.failures) {
                throw "A suite failed or was skipped: $($suite.name)"
            }
            $testCount += [int]$suite.tests
        }
        foreach ($required in @('pleos.nextkey.MysqlFlowTests','pleos.nextkey.core.DevelopmentReviewFlowTests')) {
            if (!@($suites | Where-Object { $_.name -eq $required -and [int]$_.tests -gt 0 }).Count) {
                throw "A required MySQL suite did not run: $required"
            }
        }
        Write-Host "All $testCount tests passed without skips. Starting packaged-JAR checks."
        $jar = @(Get-ChildItem -LiteralPath 'build/libs' -Filter '*.jar' | Where-Object { $_.Name -notlike '*-plain.jar' })
        if ($jar.Count -ne 1) { throw 'Expected exactly one bootJar in build/libs.' }
        $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java.exe).Source }
        foreach ($checkMode in @('dev','rejectDevSchema','prod')) {
            $profile = if ($checkMode -eq 'dev') { 'dev' } else { 'prod' }
            if ($checkMode -eq 'prod') {
                $env:NEXTKEY_DB_URL=$env:NEXTKEY_DB_URL.Replace('/nextkey_test?', '/nextkey_prod_check?')
                $env:SPRING_DATASOURCE_URL=$env:NEXTKEY_DB_URL
                $env:SPRING_FLYWAY_URL=$env:NEXTKEY_DB_URL
            }
            $log = Join-Path $directory ("app-" + $checkMode + ".log")
            $opts = @('-jar', ('"{0}"' -f $jar[0].FullName), "--spring.profiles.active=$profile",
                '--server.address=127.0.0.1','--server.port=0','--spring.main.banner-mode=off')
            $app = Start-Process -FilePath $java -ArgumentList $opts -PassThru -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError ($log+'.err')
            try {
                Write-Host "Checking packaged application ($checkMode)."
                if ($checkMode -eq 'rejectDevSchema') {
                    for ($i=0; $i -lt 360 -and !$app.HasExited; $i++) { Start-Sleep -Milliseconds 500 }
                    if (!$app.HasExited -or $app.ExitCode -eq 0 -or
                        !(Select-String -LiteralPath $log,($log+'.err') -Pattern 'DEVELOPMENT_SCHEMA_NOT_ALLOWED' -Quiet)) {
                        throw 'Production must decline the database containing development fixtures.'
                    }
                    Write-Host 'Packaged application refused the development schema in prod.'
                    continue
                }
                $listenPort = $null
                for ($i=0; $i -lt 360; $i++) {
                    if ($app.HasExited) { throw "Packaged application exited during $profile startup." }
                    $started = Select-String -LiteralPath $log -Pattern 'Tomcat started on port (\d+)' | Select-Object -Last 1
                    if ($started -and (Select-String -LiteralPath $log -SimpleMatch 'Started NextkeyApplication' -Quiet)) {
                        $listenPort=$started.Matches[0].Groups[1].Value; break
                    }
                    Start-Sleep -Milliseconds 500
                }
                if (!$listenPort) { throw "Packaged application failed to listen in $profile." }
                $base = "http://127.0.0.1:$listenPort/api"
                $body = @{username='demo-customer';password=$env:NEXTKEY_DEV_PASSWORD} | ConvertTo-Json -Compress
                if ($profile -eq 'dev') {
                    $token = (Invoke-RestMethod -Method Post -Uri "$base/auth/token" -Body $body -ContentType application/json).token
                    $history = Invoke-RestMethod -Uri "$base/me/history" -Headers @{Authorization="Bearer $token"}
                    if (!($history | Where-Object { $_.state -eq 'ENDED' -and $_.reservationId -eq 'demo-reservation' })) {
                        throw 'Packaged application could not recover the persisted trip.'
                    }
                } else {
                    foreach ($check in @('login','previousBearer')) {
                        $code=0
                        try {
                            if ($check -eq 'login') { Invoke-RestMethod -Method Post -Uri "$base/auth/token" -Body $body -ContentType application/json | Out-Null }
                            else { Invoke-RestMethod -Uri "$base/me/history" -Headers @{Authorization="Bearer $token"} | Out-Null }
                        } catch { if ($_.Exception.Response) { $code=[int]$_.Exception.Response.StatusCode } }
                        if ($code -ne 401) { throw "Expected HTTP 401 for development credential in prod ($check)." }
                    }
                }
                Write-Host "Packaged application check passed ($profile)."
            } finally {
                if (!$app.HasExited) { $app.Kill(); $app.WaitForExit() }
                $app.Dispose()
            }
        }
        [ordered]@{ completedAt=[DateTimeOffset]::UtcNow.ToString('o'); mysqlVersion=$probe;
            tests=$testCount; suites=$suites.Count; failures=0; errors=0; skipped=0;
            packagedChecks=@('dev persisted history','prod rejects demo schema','prod rejects demo login and prior bearer')
        } | ConvertTo-Json | Set-Content -LiteralPath $summaryPath -Encoding utf8
        Write-Host 'PASS: isolated MySQL tests, executable JAR and production guards.'
    } finally { Pop-Location }
} catch {
    foreach ($log in @('init.log','server.log')) {
        $logPath = Join-Path $directory $log
        if (Test-Path -LiteralPath $logPath) {
            Get-Content -LiteralPath $logPath -Tail 25 |
                Where-Object { $_ -match '\[ERROR\]|\[Warning\]' -and $_ -notmatch 'password|IDENTIFIED|CREATE USER|ALTER USER' } |
                ForEach-Object { Write-Warning $_ }
        }
    }
    throw
} finally {
    if ($daemon -and !$daemon.HasExited) {
        $env:MYSQL_PWD = $rootPassword
        try { & (Join-Path $MysqlBin 'mysqladmin.exe') --no-defaults --connect-timeout=2 --protocol=TCP '--host=127.0.0.1' "--port=$port" --user=nextkey_stop shutdown 2>$null | Out-Null } catch {}
        if (!$daemon.WaitForExit(7000)) { $daemon.Kill(); $daemon.WaitForExit() }
    }
    foreach ($name in $variables) { [Environment]::SetEnvironmentVariable($name, $original[$name], 'Process') }
    $target = [IO.Path]::GetFullPath($directory)
    if ([IO.Path]::GetDirectoryName($target).TrimEnd('\') -eq [IO.Path]::GetFullPath($tempRoot).TrimEnd('\') -and
        [IO.Path]::GetFileName($target) -match '^nextkey-test-[0-9a-f]{32}$') {
        if (Test-Path -LiteralPath (Join-Path $target 'init.sql')) { Remove-Item -LiteralPath (Join-Path $target 'init.sql') }
        Remove-Item -LiteralPath $target -Recurse -Force
    }
}
