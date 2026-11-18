[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9_][A-Za-z0-9_.-]{0,62}$')]
    [string]$Tag = ('build-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssfff')),
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$project = 'nextkey-dev-261112-9db7'
$region = 'asia-northeast3'
$repository = "$region-docker.pkg.dev/$project/nextkey/nextkey-api"
$image = "${repository}:$Tag"
$account = "projects/$project/serviceAccounts/nextkey-build@$project.iam.gserviceaccount.com"
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildRoot = [IO.Path]::GetFullPath((Join-Path $root 'build'))
$scratch = Join-Path $buildRoot ('gcp-image-' + [guid]::NewGuid().ToString('N'))
$context = Join-Path $scratch 'context'
$init = Join-Path $scratch 'stage.gradle'
$config = Join-Path $scratch 'cloudbuild.json'
$utf8 = [Text.UTF8Encoding]::new($false)
$oldJavaHome = $env:JAVA_HOME
$oldCredentialOverride = $env:CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE

Push-Location -LiteralPath $root
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    if (!$oldCredentialOverride -and $env:GOOGLE_APPLICATION_CREDENTIALS) {
        $env:CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE = $env:GOOGLE_APPLICATION_CREDENTIALS
    }
    $gcloud = (Get-Command gcloud.cmd -CommandType Application | Select-Object -First 1).Source
    [IO.Directory]::CreateDirectory($context) | Out-Null

    # Ask Gradle for this build's actual executable archive, even if build/libs has older JARs.
    $stagingTask = @'
gradle.projectsEvaluated {
    def project = gradle.rootProject
    def archive = project.tasks.named('bootJar')
    project.tasks.register('stageNextkeyCloudJar', Copy) {
        dependsOn archive
        from archive.flatMap { it.archiveFile }
        into project.providers.gradleProperty('nextkeyImageStage').get()
        rename { 'nextkey.jar' }
    }
}
'@
    [IO.File]::WriteAllText($init, $stagingTask, $utf8)
    & .\gradlew.bat --console=plain --init-script $init "-PnextkeyImageStage=$context" stageNextkeyCloudJar | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Gradle bootJar/staging failed ($LASTEXITCODE)." }
    $jar = Join-Path $context 'nextkey.jar'
    $jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "JAR SHA-256: $jarHash"

    # Keep the runtime identical to the repository Dockerfile. Only the JAR's source changes.
    $dockerfile = [IO.File]::ReadAllText((Join-Path $root 'Dockerfile')).Replace("`r`n","`n")
    $stages = [regex]::Matches($dockerfile, '(?im)^FROM[ \t]+.+$')
    if ($stages.Count -ne 2 -or $stages[0].Value -notmatch '(?i)\sAS\s+build$') {
        throw 'Expected a build stage followed by one runtime stage in Dockerfile.'
    }
    $runtime = $dockerfile.Substring($stages[1].Index)
    $copyJar = [regex]'(?m)^COPY --from=build /workspace/build/libs/\*\.jar /app/nextkey\.jar$'
    if ($copyJar.Matches($runtime).Count -ne 1) { throw 'Expected exactly one runtime COPY of the bootJar.' }
    [IO.File]::WriteAllText((Join-Path $context 'Dockerfile'), $copyJar.Replace($runtime,'COPY nextkey.jar /app/nextkey.jar'), $utf8)
    [IO.File]::WriteAllText((Join-Path $context '.gcloudignore'), "*`n!/Dockerfile`n!/nextkey.jar`n", $utf8)

    $docker = 'gcr.io/cloud-builders/docker'
    $verify = 'test "$$(id -u)" = 10001 && test -s "$$NEXTKEY_PDF_FONT" && java --describe-module java.desktop >/dev/null'
    $build = @{
        steps = @(
            @{ id='build-server'; name=$docker; args=@('build','-t',$image,'.') },
            @{ id='verify-runtime'; name=$docker; args=@('run','--rm','--entrypoint','sh',$image,'-c',$verify) }
        )
        images = @($image)
        logsBucket = "gs://$project-build-logs"
        options = @{logging='GCS_ONLY'}
        timeout = '1200s'
    }
    [IO.File]::WriteAllText($config, ($build | ConvertTo-Json -Depth 6), $utf8)

    $common = @("--project=$project", "--billing-project=$project", '--quiet')
    $result = & $gcloud builds submit $context @common "--region=$region" "--config=$config" "--service-account=$account" "--gcs-source-staging-dir=gs://$project-build-source/source" --suppress-logs '--format=json(id,status,results.images)'
    if ($LASTEXITCODE -ne 0) { throw "Cloud Build failed ($LASTEXITCODE). See the build log in the $project-build-logs bucket." }
    $completed = ($result -join "`n") | ConvertFrom-Json
    $published = @($completed.results.images | Where-Object { $_.name -ceq $image })
    if ($completed.status -ne 'SUCCESS' -or $published.Count -ne 1 -or $published[0].digest -notmatch '^sha256:[0-9a-f]{64}$') {
        throw "Build $($completed.id) did not return a successful image digest for $image."
    }
    $immutable = "$repository@$($published[0].digest)"
    $stored = & $gcloud artifacts docker images describe $immutable @common '--format=value(image_summary.digest)'
    if ($LASTEXITCODE -ne 0 -or $stored -cne $published[0].digest) { throw "Registry digest verification failed for $immutable." }
    [PSCustomObject]@{ BuildId=$completed.id; Image=$immutable; Tag=$Tag; JarSha256=$jarHash }
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE = $oldCredentialOverride
    Pop-Location
    if (Test-Path -LiteralPath $scratch) {
        $resolved = (Resolve-Path -LiteralPath $scratch).Path
        if ([IO.Path]::GetDirectoryName($resolved) -ne $buildRoot -or
            [IO.Path]::GetFileName($resolved) -notmatch '^gcp-image-[0-9a-f]{32}$') {
            throw "Unexpected build staging path: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
