$ErrorActionPreference = 'Stop'
$output = Join-Path $PSScriptRoot 'build\safety-policy-test'
New-Item -ItemType Directory -Force -Path $output | Out-Null

$policy = Join-Path $PSScriptRoot 'app\src\main\java\dev\indevelopment\m3qroot\RootSafetyPolicy.java'
$redactor = Join-Path $PSScriptRoot 'app\src\main\java\dev\indevelopment\m3qroot\LogRedactor.java'
$version = Join-Path $PSScriptRoot 'app\src\main\java\dev\indevelopment\m3qroot\VersionCompare.java'
$attribution = Join-Path $PSScriptRoot 'app\src\main\java\dev\indevelopment\m3qroot\RunPayloadAttribution.java'
$policyTest = Join-Path $PSScriptRoot 'tests\RootSafetyPolicyTest.java'
$redactorTest = Join-Path $PSScriptRoot 'tests\LogRedactorTest.java'
$versionTest = Join-Path $PSScriptRoot 'tests\VersionCompareTest.java'
$attributionTest = Join-Path $PSScriptRoot 'tests\RunPayloadAttributionTest.java'

& javac -encoding UTF-8 -d $output $policy $redactor $version $attribution $policyTest $redactorTest $versionTest $attributionTest
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

& java -cp $output dev.indevelopment.m3qroot.RootSafetyPolicyTest
if ($LASTEXITCODE -ne 0) { throw "policy test failed: $LASTEXITCODE" }

& java -cp $output dev.indevelopment.m3qroot.LogRedactorTest
if ($LASTEXITCODE -ne 0) { throw "redactor test failed: $LASTEXITCODE" }

& java -cp $output dev.indevelopment.m3qroot.VersionCompareTest
if ($LASTEXITCODE -ne 0) { throw "version test failed: $LASTEXITCODE" }

& java -cp $output dev.indevelopment.m3qroot.RunPayloadAttributionTest
if ($LASTEXITCODE -ne 0) { throw "attribution test failed: $LASTEXITCODE" }
