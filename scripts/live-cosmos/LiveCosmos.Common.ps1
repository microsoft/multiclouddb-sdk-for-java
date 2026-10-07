$mavenOutputSuppressionPattern =
  '^(?:NOTE:\s*)?Picked up (?:JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS):'
function Write-FilteredMavenOutput {
  param(
    [Parameter(Mandatory, ValueFromPipeline)]
    [AllowNull()]
    [object]$InputObject
  )
  process {
    if ("$InputObject" -notmatch
        $mavenOutputSuppressionPattern) {
      Write-Host $InputObject
    }
  }
}

$forbiddenJvmPropertyName =
  '(?:cosmos\.(?:key|endpoint)|COSMOS_(?:KEY|ENDPOINT)|jdk\.(?:tls|certpath)\.disabledAlgorithms|java\.security\.properties|user\.home|surefire\.systemPropertiesFile|azure\.client\.(?:secret|certificate\.(?:path|password))|AZURE_CLIENT_SECRET|AZURE_CLIENT_CERTIFICATE_(?:PATH|PASSWORD)|AZURE_TOKEN_CREDENTIALS)'
$forbiddenJvmPropertyToken =
  "(?:[""']?$forbiddenJvmPropertyName|`"$forbiddenJvmPropertyName`"|'$forbiddenJvmPropertyName')"
$forbiddenJvmPropertyPattern =
  "(?i)(?<![A-Za-z0-9_.])(?:-D)?$forbiddenJvmPropertyToken(?:\s*=|\s|$)"
$forbiddenJvmArgumentIndirectionPattern =
  '(?i)@\{[^}\r\n]+\}|(?:^|\s)["'']?@(?!\{)[^\s"'']+'
$forbiddenJvmExecutableOptionPattern =
  '(?i)(?:^|\s)["'']?(?:-javaagent:|-agentlib:|-agentpath:|-Xrun[^\s"'']*|-Xbootclasspath/a:|--(?:class-path|module-path|upgrade-module-path|patch-module)(?:=|\s+)|-(?:cp|classpath|p)(?:=|\s+)|-Djava\.system\.class\.loader(?:=|\s)|-XX:(?:OnError|OnOutOfMemoryError)=)'
$forbiddenMavenProjectPropertyName =
  'maven\.(?:multiModuleProjectDirectory|projectBasedir|ext\.class\.path)'
$forbiddenMavenProjectPropertyToken =
  "(?:[""']?$forbiddenMavenProjectPropertyName|`"$forbiddenMavenProjectPropertyName`"|'$forbiddenMavenProjectPropertyName')"
$forbiddenMavenProjectPropertyPattern =
  "(?i)(?:^|\s)[""']?(?:-D\s*|--define(?:=|\s+))$forbiddenMavenProjectPropertyToken(?:\s*=|\s|$)"
$forbiddenNestedJvmConfigPattern =
  "$forbiddenJvmPropertyPattern|$forbiddenJvmArgumentIndirectionPattern|$forbiddenJvmExecutableOptionPattern|(?:^|\s)[""']?(?-i:--(?:file|settings|global-settings|toolchains|global-toolchains)(?:\s+|=)\S|-(?:s|t)(?:\s+|=)?\S|-(?:gs|gt)(?:\s+|=)\S|-f(?!(?:ae|f|n|npr|npu|nsu)(?:\s|$))(?:\s+|=|\S))|$forbiddenMavenProjectPropertyPattern"
$forbiddenMavenRepositoryPropertyName =
  'maven\.repo\.local'
$forbiddenMavenRepositoryPropertyToken =
  "(?:[""']?$forbiddenMavenRepositoryPropertyName|`"$forbiddenMavenRepositoryPropertyName`"|'$forbiddenMavenRepositoryPropertyName')"
$forbiddenMavenRepositoryRedirectPattern =
  "(?i)(?:^|\s)[""']?(?:-D\s*|--define(?:=|\s+))$forbiddenMavenRepositoryPropertyToken(?:\s*=|\s|$)"
$forbiddenMavenConfigPattern =
  "$forbiddenNestedJvmConfigPattern|$forbiddenMavenRepositoryRedirectPattern"
$forbiddenSurefireExecutionPropertyName =
  '(?:argLine|jvm|maven\.surefire\.debug)'
$forbiddenSurefireExecutionPropertyToken =
  "(?:[""']?$forbiddenSurefireExecutionPropertyName|`"$forbiddenSurefireExecutionPropertyName`"|'$forbiddenSurefireExecutionPropertyName')"
$forbiddenSurefireExecutionPropertyPattern =
  "(?im)(?:^|\s)[""']?(?:-D\s*|--define(?:=|\s+))$forbiddenSurefireExecutionPropertyToken(?:\s*=|\s|$)"
function Test-ForbiddenMavenConfiguration {
  param([string]$Value)
  if ([string]::IsNullOrWhiteSpace($Value)) { return $false }
  if ($Value -match $forbiddenMavenConfigPattern `
      -or $Value -match $forbiddenSurefireExecutionPropertyPattern) { return $true }
  return $false
}

function Get-LiveCosmosMavenArguments {
  param(
    [Parameter(Mandatory)]
    [string]$Endpoint,

    [Parameter(Mandatory)]
    [string]$TempDirectory
  )
  $effectivePom = Join-Path $TempDirectory `
    'live-cosmos-effective-pom.xml'
  $providerDependencyClasspathFile = Join-Path $TempDirectory `
    'live-cosmos-test-classpath.txt'
  return @(
    '-Plive-cosmos',
    '-pl',
    'multiclouddb-conformance',
    '-am',
    '-Dtest=LiveCosmosEntraAuthenticationTest#entraAuthenticationPerformsCreateReadDelete',
    '-Dsurefire.failIfNoSpecifiedTests=false',
    "-Dcosmos.endpoint=$Endpoint",
    '-Djunit.jupiter.execution.timeout.default=60s',
    '-DtrimStackTrace=false',
    "-Doutput=$effectivePom",
    '-DincludeScope=test',
    "-Dmdep.outputFile=$providerDependencyClasspathFile"
  )
}

function Test-ExactStringArray {
  param(
    [Parameter(Mandatory)]
    [object[]]$Actual,

    [Parameter(Mandatory)]
    [string[]]$Expected
  )
  if ($Actual.Count -ne $Expected.Count) {
    return $false
  }
  for ($index = 0; $index -lt $Expected.Count; $index++) {
    if ($Actual[$index] -isnot [string] `
        -or $Actual[$index] -cne $Expected[$index]) {
      return $false
    }
  }
  return $true
}
