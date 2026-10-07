param(
  [Parameter(Mandatory)]
  [string]$SourceDirectory,

  [Parameter(Mandatory)]
  [string]$TempDirectory
)

$ErrorActionPreference = 'Stop'
$commonScript = Join-Path $PSScriptRoot 'LiveCosmos.Common.ps1'
if (-not (Test-Path -LiteralPath $commonScript -PathType Leaf)) {
  throw "Required live Cosmos helper is missing: $commonScript"
}
. $commonScript
$errors = [System.Collections.Generic.List[string]]::new()
$endpoint = $env:COSMOS_ENDPOINT
$sentinelMethod =
  'entraAuthenticationPerformsCreateReadDelete'
$effectivePom = Join-Path $TempDirectory `
  'live-cosmos-effective-pom.xml'
$argumentManifest = Join-Path $TempDirectory `
  'live-cosmos-maven-arguments.json'
$providerDependencyClasspathFile = Join-Path `
  $TempDirectory 'live-cosmos-test-classpath.txt'
foreach ($stalePreflightArtifact in @(
    $effectivePom,
    $argumentManifest,
    $providerDependencyClasspathFile)) {
  if (Test-Path -LiteralPath $stalePreflightArtifact) {
    Remove-Item -LiteralPath $stalePreflightArtifact `
      -Force -ErrorAction Stop
  }
  if (Test-Path -LiteralPath $stalePreflightArtifact) {
    throw "Stale preflight artifact could not be removed: $([IO.Path]::GetFileName($stalePreflightArtifact))"
  }
}
$trustedProjectBase = (Resolve-Path -LiteralPath `
  $SourceDirectory).Path
$currentProjectBase = (Resolve-Path -LiteralPath `
  (Get-Location).Path).Path
if ($currentProjectBase -cne $trustedProjectBase) {
  $errors.Add(
    'Maven must run from the trusted Build.SourcesDirectory checkout.')
}

foreach ($baseVariable in @(
    'MAVEN_PROJECTBASEDIR',
    'MAVEN_BASEDIR')) {
  $configuredProjectBase = [Environment]::GetEnvironmentVariable(
    $baseVariable)
  if (-not [string]::IsNullOrWhiteSpace($configuredProjectBase)) {
    try {
      $resolvedProjectBase = (Resolve-Path -LiteralPath `
        $configuredProjectBase).Path
      if ($resolvedProjectBase -cne $trustedProjectBase) {
        $errors.Add(
          "$baseVariable must resolve to Build.SourcesDirectory.")
      }
    } catch {
      $errors.Add(
        "$baseVariable is set but does not resolve to the trusted checkout.")
    }
  }
}

foreach ($relativeConfigPath in @(
    '.mvn/maven.config',
    '.mvn/jvm.config')) {
  $configPath = Join-Path $trustedProjectBase $relativeConfigPath
  if (Test-Path -LiteralPath $configPath -PathType Leaf) {
    Write-Host "Inspecting Maven configuration file: $relativeConfigPath"
    $configText = Get-Content -LiteralPath $configPath -Raw
    if (Test-ForbiddenMavenConfiguration $configText) {
      $errors.Add(
        "Maven configuration file '$relativeConfigPath' contains forbidden project, credential, endpoint, or security-policy injection.")
    }
  }
}
$extensionsPath = Join-Path $trustedProjectBase '.mvn/extensions.xml'
if (Test-Path -LiteralPath $extensionsPath) {
  $errors.Add(
    "Maven core extension file '.mvn/extensions.xml' is not permitted in the live WIF pipeline.")
}

$modulePomPath = Join-Path $trustedProjectBase `
  'multiclouddb-conformance/pom.xml'
if (-not (Test-Path -LiteralPath $modulePomPath -PathType Leaf)) {
  $errors.Add(
    'The multiclouddb-conformance module POM is missing.')
} else {
  [xml]$modulePom = Get-Content -LiteralPath $modulePomPath -Raw
  $namespace = [System.Xml.XmlNamespaceManager]::new(
    $modulePom.NameTable)
  $namespace.AddNamespace(
    'm', $modulePom.DocumentElement.NamespaceURI)
  $moduleLiveProfiles = @($modulePom.SelectNodes(
    '/m:project/m:profiles/m:profile[m:id="live-cosmos"]',
    $namespace))
  if ($moduleLiveProfiles.Count -ne 1) {
    $errors.Add(@'
The live-cosmos profile must be declared exactly once in
multiclouddb-conformance/pom.xml. A settings-only or root-only profile does
not securely replace that module's emulator Surefire configuration.
'@
    )
  }
}

if ([string]::IsNullOrWhiteSpace($endpoint) -or $endpoint.StartsWith('$(')) {
  $errors.Add('Set the non-secret COSMOS_ENDPOINT pipeline variable to the live Cosmos DB account URI.')
} else {
  $uri = $null
  if (-not [System.Uri]::TryCreate($endpoint, [System.UriKind]::Absolute, [ref]$uri) `
      -or $uri.Scheme -ne 'https' `
      -or $uri.UserInfo `
      -or $uri.Query `
      -or $uri.Fragment `
      -or (-not $uri.IsDefaultPort -and $uri.Port -ne 443) `
      -or $uri.AbsolutePath -ne '/' `
      -or $uri.Host -notmatch '\.documents\.azure\.(com|us|cn)$') {
    $errors.Add('COSMOS_ENDPOINT must be an HTTPS Cosmos account URI such as https://account.documents.azure.com:443/.')
  }
}

$forbiddenCredentialVariables = @(
  'COSMOS_KEY',
  'AZURE_CLIENT_SECRET',
  'AZURE_CLIENT_CERTIFICATE_PATH',
  'AZURE_CLIENT_CERTIFICATE_PASSWORD'
)
foreach ($name in $forbiddenCredentialVariables) {
  $value = [Environment]::GetEnvironmentVariable($name)
  if (-not [string]::IsNullOrWhiteSpace($value)) {
    $errors.Add("Forbidden credential environment variable '$name' is set.")
  }
}

$forbiddenOptionVariables = @(
  'JAVA_TOOL_OPTIONS',
  '_JAVA_OPTIONS',
  'JDK_JAVA_OPTIONS',
  'MAVEN_OPTS',
  'MAVEN_DEBUG_OPTS',
  'MAVEN_ARGS'
)
foreach ($name in $forbiddenOptionVariables) {
  $value = [Environment]::GetEnvironmentVariable($name)
  if (-not [string]::IsNullOrWhiteSpace($value) `
      -and (Test-ForbiddenMavenConfiguration $value)) {
    $errors.Add(
      "Environment variable '$name' contains forbidden Maven/JVM injection.")
  }
}

if ($errors.Count -eq 0) {
  # Keep this data-only array identical for profile inspection,
  # effective-POM inspection, classpath preparation, and the
  # later WIF test execution.
  $liveMavenArguments = @(
    Get-LiveCosmosMavenArguments `
      -Endpoint $endpoint -TempDirectory $TempDirectory)
  $profileMetadata =
    [System.Collections.Generic.List[string]]::new()
  & mvn help:active-profiles `
    @liveMavenArguments 2>&1 |
    ForEach-Object {
      if ("$_" -match
          '^(?:\[[A-Z]+\]\s+)?\s*-\s+\S+\s+\(source:') {
        $profileMetadata.Add("$_")
      }
      $_
    } |
    Write-FilteredMavenOutput
  $profilesExitCode = $LASTEXITCODE
  $profiles = if (Test-Path -LiteralPath $effectivePom -PathType Leaf) {
    Get-Content -LiteralPath $effectivePom
  } else {
    $profileMetadata
  }
  # Maven returns exit code 0 for an unknown -P profile, so preserve
  # this exact profile-list check. The filtered help output streams
  # immediately; only bounded profile source lines are retained when
  # the help goal does not produce its requested output file.
  if ($profilesExitCode -ne 0) {
    $errors.Add('Maven could not activate the live-cosmos profile; inspect the preflight log.')
  } elseif (-not ($profiles | Select-String -Pattern '^(?:\[[A-Z]+\]\s+)?\s*-\s+live-cosmos\s+\(source:')) {
    $errors.Add(@'
The required Maven profile 'live-cosmos' does not exist or did not activate.
Add it with a dedicated com.multiclouddb.conformance.LiveCosmosEntraAuthenticationTest
sentinel method named entraAuthenticationPerformsCreateReadDelete. Use a
standalone final class with no class/extension annotations, inheritance,
lifecycle hooks, fields, helper members, or extra method annotations. The test
must use fully qualified com.multiclouddb.api config, provider, client, and
factory types to construct a live-specific MulticloudDbClientConfig whose only
connection setting is java.lang.System.getProperty("cosmos.endpoint"), with no
key/auth entries; obtain the run-scoped key from the fully qualified
com.multiclouddb.conformance.ConformanceHarness.uniqueKey helper. It must not
call ConformanceConfig.forProvider(COSMOS), which defaults to the emulator key.
Create the client in try-with-resources, use the administrator-selected shared
fixture database/container, delete the exact run-scoped item in finally, and
fail rather than skip when endpoint, Azure CLI authentication, RBAC, or
fixtures are unavailable. This contract provides per-run item isolation only;
it does not provide per-run resource provisioning or crash-proof cleanup. The
profile must retain Maven's default test source and output directories, omit
compiler/source/classpath substitutions, omit credential and endpoint
injection, and require cosmos.endpoint. The sentinel must not mutate system or
Java security properties. Its compiled test classpath must contain only the
production Cosmos, Dynamo, and Spanner SPI descriptors/classes from their
canonical reactor build outputs; test, generated, shadowed, malformed,
duplicate, or dependency provider registrations are unsupported.
'@
    )
  }
}

if ($errors.Count -eq 0) {
  $sentinelSource = Join-Path $SourceDirectory `
    'multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/LiveCosmosEntraAuthenticationTest.java'
  if (-not (Test-Path -LiteralPath $sentinelSource -PathType Leaf)) {
    $errors.Add(@'
The required LiveCosmosEntraAuthenticationTest source file does not exist.
It must define entraAuthenticationPerformsCreateReadDelete as the reviewed
live Entra create/read/delete contract.
'@
)
  } else {
    $sentinelText = Get-Content -LiteralPath $sentinelSource -Raw
    if ($sentinelText -match '\\u+[0-9A-Fa-f]{4}') {
      $errors.Add(
        'LiveCosmosEntraAuthenticationTest must not contain Java Unicode escapes.')
    }
    function Get-JavaStructuralText {
      param([string]$Source)
      $output = [char[]](' ' * $Source.Length)
      $state = 'code'
      for ($index = 0; $index -lt $Source.Length; $index++) {
        $character = $Source[$index]
        $next = if ($index + 1 -lt $Source.Length) {
          $Source[$index + 1]
        } else { [char]0 }
        $afterNext = if ($index + 2 -lt $Source.Length) {
          $Source[$index + 2]
        } else { [char]0 }

        if ($character -eq "`r" -or $character -eq "`n") {
          $output[$index] = $character
          if ($state -eq 'lineComment') { $state = 'code' }
          continue
        }
        if ($state -eq 'lineComment') { continue }
        if ($state -eq 'blockComment') {
          if ($character -eq '*' -and $next -eq '/') {
            $state = 'code'
            $index++
          }
          continue
        }
        if ($state -eq 'string') {
          if ($character -eq '\') {
            $index++
          } elseif ($character -eq '"') {
            $state = 'code'
          }
          continue
        }
        if ($state -eq 'character') {
          if ($character -eq '\') {
            $index++
          } elseif ($character -eq "'") {
            $state = 'code'
          }
          continue
        }
        if ($state -eq 'textBlock') {
          if ($character -eq '"' -and $next -eq '"' -and
              $afterNext -eq '"' -and
              ($index -eq 0 -or $Source[$index - 1] -ne '\')) {
            $state = 'code'
            $index += 2
          }
          continue
        }

        if ($character -eq '/' -and $next -eq '/') {
          $state = 'lineComment'
          $index++
        } elseif ($character -eq '/' -and $next -eq '*') {
          $state = 'blockComment'
          $index++
        } elseif ($character -eq '"' -and $next -eq '"' -and
            $afterNext -eq '"') {
          $state = 'textBlock'
          $index += 2
        } elseif ($character -eq '"') {
          $literalEnd = $index + 1
          while ($literalEnd -lt $Source.Length) {
            if ($Source[$literalEnd] -eq '\') {
              $literalEnd += 2
              continue
            }
            if ($Source[$literalEnd] -eq '"') { break }
            $literalEnd++
          }
          if ($literalEnd -ge $Source.Length) {
            $state = 'string'
          } else {
            $literal = $Source.Substring(
              $index, $literalEnd - $index + 1)
            if ($literal -eq '"endpoint"' `
                -or $literal -eq '"cosmos.endpoint"') {
              for ($literalIndex = $index;
                  $literalIndex -le $literalEnd;
                  $literalIndex++) {
                $output[$literalIndex] = $Source[$literalIndex]
              }
            }
            $index = $literalEnd
          }
        } elseif ($character -eq "'") {
          $state = 'character'
        } else {
          $output[$index] = $character
        }
      }
      return -join $output
    }

    function Get-BalancedJavaBlock {
      param(
        [string]$Structure,
        [int]$OpenBraceIndex
      )
      $depth = 0
      for ($index = $OpenBraceIndex; $index -lt $Structure.Length; $index++) {
        if ($Structure[$index] -eq '{') {
          $depth++
        } elseif ($Structure[$index] -eq '}') {
          $depth--
          if ($depth -eq 0) {
            return $Structure.Substring(
              $OpenBraceIndex, $index - $OpenBraceIndex + 1)
          }
        }
      }
      return ''
    }

    function Get-JavaBraceDepth {
      param(
        [string]$Structure,
        [int]$Position
      )
      $depth = 0
      for ($index = 0; $index -lt $Position; $index++) {
        if ($Structure[$index] -eq '{') {
          $depth++
        } elseif ($Structure[$index] -eq '}') {
          $depth--
        }
      }
      return $depth
    }

    $sentinelStructure = Get-JavaStructuralText $sentinelText
    $classPattern =
      '\bpublic\s+final\s+class\s+LiveCosmosEntraAuthenticationTest\s*\{'
    $classMatch = $null
    foreach ($candidate in [regex]::Matches(
        $sentinelStructure, $classPattern)) {
      if ((Get-JavaBraceDepth $sentinelStructure $candidate.Index) -eq 0) {
        $classMatch = $candidate
        break
      }
    }
    $classStructure = ''
    $classBodyStart = -1
    $classHasAnnotations = $false
    if ($null -ne $classMatch) {
      $classPrefix = $sentinelStructure.Substring(
        0, $classMatch.Index)
      $classHasAnnotations =
        $classPrefix -match '(?m)^\s*@'
    }
    if ($null -eq $classMatch `
        -or $classHasAnnotations `
        -or $sentinelStructure -notmatch
          '(?m)^\s*package\s+com\.multiclouddb\.conformance\s*;') {
      $errors.Add(@'
The sentinel source must declare the top-level
public final com.multiclouddb.conformance.LiveCosmosEntraAuthenticationTest
class without class annotations, inheritance, or implemented interfaces.
'@
)
    } else {
      $classBodyStart = $sentinelStructure.IndexOf(
        '{', $classMatch.Index)
      $classStructure = Get-BalancedJavaBlock `
        $sentinelStructure $classBodyStart
    }

    if ($classStructure -match '\bConformanceConfig\b' `
        -or $classStructure -match
          '\b(?:CosmosConstants\s*\.\s*)?CONFIG_KEY\b' `
        -or $classStructure -match
          '\bConformanceHarness\s*\.\s*createClient\s*\(' `
        -or $classStructure -match '\.\s*key\s*\(' `
        -or $classStructure -match '\.\s*auth\s*\(') {
      $errors.Add(@'
LiveCosmosEntraAuthenticationTest references ConformanceConfig,
ConformanceHarness.createClient, or a key/auth configuration API. Build a
live-specific MulticloudDbClientConfig with only the pipeline-supplied
endpoint so DefaultAzureCredential is selected.
'@
)
    }
    if ($classStructure -match '@DisplayName\s*\(') {
      $errors.Add(
        'LiveCosmosEntraAuthenticationTest must not declare @DisplayName; use the exact default method name.')
    }

    $methodPattern = "(?s)@Test\s+(?:public\s+)?void\s+$sentinelMethod\s*\(\s*\)(?:\s+throws\s+[\w.,\s]+)?\s*\{"
    $methodMatch = $null
    foreach ($candidate in [regex]::Matches(
        $classStructure, $methodPattern)) {
      if ((Get-JavaBraceDepth $classStructure $candidate.Index) -eq 1) {
        $methodMatch = $candidate
        break
      }
    }
    $sentinelMethodStructure = ''
    if ($null -eq $methodMatch) {
      $errors.Add(
        "LiveCosmosEntraAuthenticationTest must define the exact @Test method '$sentinelMethod'.")
    } else {
      $bodyStartInClass = $classStructure.IndexOf(
        '{', $methodMatch.Index)
      $sentinelMethodStructure = Get-BalancedJavaBlock `
        $classStructure $bodyStartInClass
      if ([string]::IsNullOrWhiteSpace($sentinelMethodStructure)) {
        $errors.Add(
          "Could not inspect the body of LiveCosmosEntraAuthenticationTest#$sentinelMethod.")
      } else {
        $methodEndInClass =
          $bodyStartInClass + $sentinelMethodStructure.Length
        $membersBeforeMethod = $classStructure.Substring(
          1, $methodMatch.Index - 1)
        $membersAfterMethod = $classStructure.Substring(
          $methodEndInClass,
          $classStructure.Length - $methodEndInClass - 1)
        if (-not [string]::IsNullOrWhiteSpace(
            $membersBeforeMethod + $membersAfterMethod)) {
          $errors.Add(@'
LiveCosmosEntraAuthenticationTest must be a self-contained sentinel whose
only class member is entraAuthenticationPerformsCreateReadDelete. Fields,
constructors, helper methods, nested types, additional tests, and JUnit
lifecycle hooks are not permitted; code review must assess any future
expansion rather than relying on this source gate.
'@
)
        }
      }
    }

    $missingContract = [System.Collections.Generic.List[string]]::new()
    if ($sentinelMethodStructure -match
        '(?:\b(?:catch|if|else|for|while|do|switch|synchronized)\b|\bassertDoesNotThrow\s*\(|->)') {
      $missingContract.Add(
        'no conditional, loop, catch, synchronized, assertDoesNotThrow, or lambda wrappers')
    }
    $forbiddenSystemPropertyAccessPattern =
      '(?x)(?:java\s*\.\s*lang\s*\.\s*)?System\s*(?:\.|::)\s*(?:setProperty|clearProperty|setProperties|getProperties)\b|(?<![\w.])(?:setProperty|clearProperty|setProperties|getProperties)\s*\('
    $forbiddenSecurityRuntimePattern =
      '(?x)(?:java\s*\.\s*security\s*\.\s*)?Security\s*(?:\.|::)\s*(?:setProperty|addProvider|insertProviderAt|removeProvider)\b|(?<![\w.])(?:addProvider|insertProviderAt|removeProvider)\s*\('
    $forbiddenSystemStaticImportPattern =
      '(?m)^\s*import\s+static\s+java\.lang\.System\.(?:setProperty|clearProperty|setProperties|getProperties|\*)\s*;'
    $forbiddenSecurityStaticImportPattern =
      '(?m)^\s*import\s+static\s+java\.security\.Security\.(?:setProperty|addProvider|insertProviderAt|removeProvider|\*)\s*;'
    if ($sentinelStructure -match
          $forbiddenSystemStaticImportPattern `
        -or $sentinelMethodStructure -match
          $forbiddenSystemPropertyAccessPattern) {
      $missingContract.Add(
        'no System property mutation or mutable System.getProperties access')
    }
    if ($sentinelStructure -match
          $forbiddenSecurityStaticImportPattern `
        -or $sentinelMethodStructure -match
          $forbiddenSecurityRuntimePattern) {
      $missingContract.Add(
        'no java.security.Security runtime policy/provider mutation')
    }
    $apiPrefix =
      'com\s*\.\s*multiclouddb\s*\.\s*api\s*\.'
    $configTypePattern =
      "${apiPrefix}MulticloudDbClientConfig"
    $providerTypePattern =
      "${apiPrefix}ProviderId"
    $clientTypePattern =
      "${apiPrefix}MulticloudDbClient"
    $factoryTypePattern =
      "${apiPrefix}MulticloudDbClientFactory"
    $resourceAddressTypePattern =
      "${apiPrefix}ResourceAddress"
    $configMatch = [regex]::Match(
      $sentinelMethodStructure,
      "(?s)\b$configTypePattern\s+(?<config>\w+)\s*=\s*$configTypePattern\s*\.\s*builder\s*\(\s*\)(?<chain>[^;]*)\.\s*build\s*\(\s*\)\s*;")
    if (-not $configMatch.Success `
        -or (Get-JavaBraceDepth `
          $sentinelMethodStructure $configMatch.Index) -ne 1) {
      $missingContract.Add(
        'direct method-level MulticloudDbClientConfig construction')
    } else {
      $configName = [regex]::Escape($configMatch.Groups['config'].Value)
      if (@([regex]::Matches(
          $sentinelMethodStructure,
          "\b$configName\s*=(?!=)")).Count -ne 1 `
          -or $sentinelMethodStructure -match
            "\b$configName\s*[+\-*/]=") {
        $missingContract.Add('a config variable that is never reassigned')
      }
      $configCode = $sentinelMethodStructure.Substring(
        $configMatch.Index, $configMatch.Length)
      $providerInvocations = @([regex]::Matches(
        $configMatch.Groups['chain'].Value,
        '\.\s*provider\s*\('))
      $cosmosProviderInvocations = @([regex]::Matches(
        $configMatch.Groups['chain'].Value,
        "\.\s*provider\s*\(\s*$providerTypePattern\s*\.\s*COSMOS\s*\)"))
      if ($providerInvocations.Count -ne 1 `
          -or $cosmosProviderInvocations.Count -ne 1) {
        $missingContract.Add(
          'exactly one provider invocation selecting fully qualified com.multiclouddb.api.ProviderId.COSMOS')
      }
      $endpointConnectionPattern =
        '\.\s*connection\s*\(\s*"endpoint"\s*,\s*java\s*\.\s*lang\s*\.\s*System\s*\.\s*getProperty\s*\(\s*"cosmos\.endpoint"\s*\)\s*\)'
      if ($configCode -notmatch $endpointConnectionPattern `
          -or @([regex]::Matches(
            $configCode, '\.\s*connection\s*\(')).Count -ne 1 `
          -or $configCode -match '\.\s*auth\s*\(') {
        $missingContract.Add(
          'one endpoint-only connection bound directly to java.lang.System.getProperty("cosmos.endpoint") and no auth settings')
      }

      $factoryCallPattern =
        "\b$factoryTypePattern\s*\.\s*create\s*\("
      $clientMatch = [regex]::Match(
        $sentinelMethodStructure,
        "(?s)\btry\s*\(\s*$clientTypePattern\s+(?<client>\w+)\s*=\s*$factoryTypePattern\s*\.\s*create\s*\(\s*$configName\s*\)\s*;?\s*\)\s*\{")
      if (-not $clientMatch.Success `
          -or (Get-JavaBraceDepth `
            $sentinelMethodStructure $clientMatch.Index) -ne 1 `
          -or @([regex]::Matches(
            $sentinelMethodStructure,
            $factoryCallPattern)).Count -ne 1) {
        $missingContract.Add(
          'one fully qualified com.multiclouddb.api factory/client path created from that config in try-with-resources')
      } else {
        $clientName = [regex]::Escape($clientMatch.Groups['client'].Value)
        if (@([regex]::Matches(
            $sentinelMethodStructure,
            "\b$clientName\s*=(?!=)")).Count -ne 1 `
            -or $sentinelMethodStructure -match
              "\b$clientName\s*[+\-*/]=") {
          $missingContract.Add('a factory client variable that is never reassigned')
        }
        $clientBodyStart = $sentinelMethodStructure.IndexOf(
            '{', $clientMatch.Index)
        $clientScope = Get-BalancedJavaBlock `
            $sentinelMethodStructure $clientBodyStart
        if ([string]::IsNullOrWhiteSpace($clientScope)) {
            $missingContract.Add(
              'a balanced try-with-resources client scope')
        }
        $keyMatch = [regex]::Match(
            $sentinelMethodStructure,
            '(?s)\b(?:var|MulticloudDbKey)\s+(?<key>\w+)\s*=\s*com\s*\.\s*multiclouddb\s*\.\s*conformance\s*\.\s*ConformanceHarness\s*\.\s*uniqueKey\s*\(')
        if (-not $keyMatch.Success `
            -or (Get-JavaBraceDepth `
              $sentinelMethodStructure $keyMatch.Index) -ne 1) {
          $missingContract.Add(
            'a method-level fully qualified com.multiclouddb.conformance.ConformanceHarness.uniqueKey run-scoped key')
        } else {
          $keyName = [regex]::Escape($keyMatch.Groups['key'].Value)
          if (@([regex]::Matches(
              $sentinelMethodStructure,
              "\b$keyName\s*=(?!=)")).Count -ne 1 `
              -or $sentinelMethodStructure -match
                "\b$keyName\s*[+\-*/]=") {
            $missingContract.Add('a run-scoped key variable that is never reassigned')
          }

          $addressMatch = [regex]::Match(
            $sentinelMethodStructure,
            "(?s)\b(?:var|$resourceAddressTypePattern)\s+(?<address>\w+)\s*=\s*new\s+$resourceAddressTypePattern\s*\([^;]*\)\s*;")
          if (-not $addressMatch.Success `
              -or (Get-JavaBraceDepth `
                $sentinelMethodStructure $addressMatch.Index) -ne 1) {
            $missingContract.Add(
              'a method-level com.multiclouddb.api.ResourceAddress')
          } else {
            $addressName = [regex]::Escape(
              $addressMatch.Groups['address'].Value)
            if (@([regex]::Matches(
                $sentinelMethodStructure,
                "\b$addressName\s*=(?!=)")).Count -ne 1 `
                -or $sentinelMethodStructure -match
                  "\b$addressName\s*[+\-*/]=") {
              $missingContract.Add(
                'a resource-address variable that is never reassigned')
            }

            $protectedFlow = $false
            foreach ($tryMatch in [regex]::Matches(
                $clientScope, '\btry\s*\{')) {
              $tryStart = $clientScope.IndexOf(
                '{', $tryMatch.Index)
              $tryBody = Get-BalancedJavaBlock `
                $clientScope $tryStart
              if ([string]::IsNullOrWhiteSpace($tryBody)) { continue }

              $afterTry = $tryStart + $tryBody.Length
              $afterTryText = $clientScope.Substring($afterTry)
              $finallyMatch = [regex]::Match(
                $afterTryText, '^\s*finally\s*\{')
              if (-not $finallyMatch.Success) { continue }
              $finallyStart = $afterTry + $afterTryText.IndexOf(
                '{', $finallyMatch.Index)
              $finallyBody = Get-BalancedJavaBlock `
                $clientScope $finallyStart

              $tryStatements = $tryBody.Substring(
                1, $tryBody.Length - 2)
              $finallyStatements = $finallyBody.Substring(
                1, $finallyBody.Length - 2)
              $wrappedFlowPattern =
                '(?:[{}]|->|\b(?:if|else|for|while|do|switch|try|catch|synchronized)\b)'
              if ($tryStatements -match $wrappedFlowPattern `
                  -or $finallyStatements -match $wrappedFlowPattern) {
                continue
              }

              $createPattern =
                "(?s)\b$clientName\s*\.\s*create\s*\(\s*$addressName\s*,\s*$keyName\s*,"
              $readPattern =
                "(?s)\b(?:var|DocumentResult)\s+(?<result>\w+)\s*=\s*$clientName\s*\.\s*read\s*\(\s*$addressName\s*,\s*$keyName\s*(?:,|\))"
              $createMatch = [regex]::Match(
                $tryBody, $createPattern)
              $readMatch = [regex]::Match(
                $tryBody, $readPattern)
              $deletePattern =
                "(?s)\b$clientName\s*\.\s*delete\s*\(\s*$addressName\s*,\s*$keyName\s*(?:,|\))"
              $deleteMatch = [regex]::Match(
                $finallyBody, $deletePattern)
              if ($createMatch.Success `
                  -and $readMatch.Success `
                  -and $deleteMatch.Success `
                  -and $createMatch.Index -lt $readMatch.Index) {
                $readResultName = [regex]::Escape(
                  $readMatch.Groups['result'].Value)
                $assertMatch = [regex]::Match(
                  $tryBody,
                  "(?s)\bassertNotNull\s*\(\s*$readResultName\s*\)")
                if ($assertMatch.Success `
                    -and $readMatch.Index -lt $assertMatch.Index) {
                  $protectedFlow = $true
                  break
                }
              }
            }
            if (-not $protectedFlow) {
              $missingContract.Add(
                'ordered create/read/assertNotNull protected by the finally that deletes the same client/address/key')
            }
          }
        }
      }
    }
    if ($missingContract.Count -ne 0) {
      $errors.Add(@"
LiveCosmosEntraAuthenticationTest#$sentinelMethod is missing required source
contract elements: $(($missingContract -join ', ')). The exact method must
directly bind its sole endpoint to
java.lang.System.getProperty("cosmos.endpoint"), invoke provider exactly once
with fully qualified com.multiclouddb.api.ProviderId.COSMOS, configure no key/auth entries,
obtain its run-scoped key from the fully qualified
com.multiclouddb.conformance.ConformanceHarness.uniqueKey helper, and use one
unchanged fully qualified com.multiclouddb.api.ResourceAddress variable for its
ordered create/read/verify/delete flow against the administrator-selected
shared fixture database/container. It must delete the exact run-scoped item in
finally and guarantee client close with try-with-resources even when delete
fails. This is per-item isolation, not per-run resource provisioning or
crash-proof cleanup. The final class must contain no class/extension
annotations, inheritance, lifecycle hooks, fields, helper members, or extra
method annotations, and its config/provider/client/factory references must use
the fully qualified com.multiclouddb.api types. It must not mutate System
properties or Java security policy/provider state. These checks and the
pre-test data-only SPI descriptor/class-origin validation reject known
unsafe/trivial substitutions without loading provider classes. They are not
an arbitrary-Java/plugin sandbox, so trusted-source review must still confirm
live operation and cleanup semantics.
"@
)
    }
  }

  if ($errors.Count -eq 0) {
    $currentProjectBase = (Resolve-Path -LiteralPath `
      (Get-Location).Path).Path
    if ($currentProjectBase -cne $trustedProjectBase) {
      $errors.Add(
        'Maven must run from the trusted Build.SourcesDirectory checkout.')
    }
    foreach ($baseVariable in @(
        'MAVEN_PROJECTBASEDIR',
        'MAVEN_BASEDIR')) {
      $configuredProjectBase = [Environment]::GetEnvironmentVariable(
        $baseVariable)
      if (-not [string]::IsNullOrWhiteSpace($configuredProjectBase)) {
        try {
          $resolvedProjectBase = (Resolve-Path -LiteralPath `
            $configuredProjectBase).Path
          if ($resolvedProjectBase -cne $trustedProjectBase) {
            $errors.Add(
              "$baseVariable must resolve to Build.SourcesDirectory.")
          }
        } catch {
          $errors.Add(
            "$baseVariable does not resolve to the trusted checkout.")
        }
      }
    }
    foreach ($name in $forbiddenOptionVariables) {
      $value = [Environment]::GetEnvironmentVariable($name)
      if (-not [string]::IsNullOrWhiteSpace($value) `
          -and (Test-ForbiddenMavenConfiguration $value)) {
        $errors.Add(
          "Environment variable '$name' contains forbidden Maven/JVM injection.")
      }
    }
    foreach ($relativeConfigPath in @(
        '.mvn/maven.config',
        '.mvn/jvm.config')) {
      $configPath = Join-Path $trustedProjectBase $relativeConfigPath
      if (Test-Path -LiteralPath $configPath -PathType Leaf) {
        Write-Host "Rechecking Maven configuration file: $relativeConfigPath"
        $configText = Get-Content -LiteralPath $configPath -Raw
        if (Test-ForbiddenMavenConfiguration $configText) {
          $errors.Add(
            "Maven configuration file '$relativeConfigPath' contains forbidden project, credential, endpoint, or security-policy injection.")
        }
      }
    }
    $extensionsPath = Join-Path $trustedProjectBase `
      '.mvn/extensions.xml'
    if (Test-Path -LiteralPath $extensionsPath) {
      $errors.Add(
        "Maven core extension file '.mvn/extensions.xml' is not permitted in the live WIF pipeline.")
    }
  }

  if ($errors.Count -eq 0) {
    if (Test-Path -LiteralPath $effectivePom) {
      Remove-Item -LiteralPath $effectivePom -Force -ErrorAction Stop
    }
    & mvn help:effective-pom `
      @liveMavenArguments 2>&1 |
      Write-FilteredMavenOutput
    $effectivePomExitCode = $LASTEXITCODE
    if ($effectivePomExitCode -ne 0 `
        -or -not (Test-Path -LiteralPath $effectivePom)) {
      $errors.Add('Maven could not produce the activated live-cosmos effective POM.')
    } else {
    [xml]$effective = Get-Content -LiteralPath $effectivePom -Raw
    $conformanceProjects = @($effective.SelectNodes(
      "//*[local-name()='project']" +
      "[normalize-space(*[local-name()='artifactId'])=" +
      "'multiclouddb-conformance']"))
    $surefire = if ($conformanceProjects.Count -eq 1) {
      @($conformanceProjects[0].SelectNodes(
        "./*[local-name()='build']/" +
        "*[local-name()='plugins']/*[local-name()='plugin']" +
        "[normalize-space(*[local-name()='artifactId'])=" +
        "'maven-surefire-plugin']"))
    } else {
      @()
    }
    if ($conformanceProjects.Count -ne 1 `
        -or $surefire.Count -eq 0) {
      $errors.Add(@'
The activated effective POM must contain exactly one
multiclouddb-conformance project with an inspectable maven-surefire-plugin
configuration for the LiveCosmosEntraAuthenticationTest sentinel. A plugin
from another reactor module does not satisfy this requirement.
'@
)
    } else {
      $conformanceBuild = $conformanceProjects[0].SelectSingleNode(
        "./*[local-name()='build']")
      $expectedTestSourceDirectory = [IO.Path]::GetFullPath(
        (Join-Path $trustedProjectBase `
          'multiclouddb-conformance/src/test/java'))
      $expectedTestOutputDirectory = [IO.Path]::GetFullPath(
        (Join-Path $trustedProjectBase `
          'multiclouddb-conformance/target/test-classes'))
      $effectiveTestSourceDirectory =
        $conformanceBuild.SelectSingleNode(
          "./*[local-name()='testSourceDirectory']")
      $effectiveTestOutputDirectory =
        $conformanceBuild.SelectSingleNode(
          "./*[local-name()='testOutputDirectory']")
      if ($null -eq $effectiveTestSourceDirectory `
          -or [IO.Path]::GetFullPath(
            $effectiveTestSourceDirectory.InnerText) -cne
            $expectedTestSourceDirectory `
          -or $null -eq $effectiveTestOutputDirectory `
          -or [IO.Path]::GetFullPath(
            $effectiveTestOutputDirectory.InnerText) -cne
            $expectedTestOutputDirectory) {
        $errors.Add(@'
The activated multiclouddb-conformance build must retain Maven's canonical
src/test/java source directory and target/test-classes output directory. The
preflight validates the canonical sentinel source and cannot approve an
alternate source tree or compiled-test output.
'@
        )
      }

      $unsupportedTestPathNodes = @(
        $conformanceBuild.SelectNodes(
          "./*[local-name()='plugins']/*[local-name()='plugin']" +
          "[normalize-space(*[local-name()='artifactId'])=" +
          "'maven-surefire-plugin']" +
          "//*[local-name()='testClassesDirectory' or " +
          "local-name()='additionalClasspathElements' or " +
          "local-name()='additionalClasspathDependencies' or " +
          "local-name()='dependenciesToScan']"),
        $conformanceBuild.SelectNodes(
          "./*[local-name()='plugins']/*[local-name()='plugin']" +
          "[normalize-space(*[local-name()='artifactId'])=" +
          "'maven-compiler-plugin']" +
          "//*[local-name()='compileSourceRoots' or " +
          "local-name()='outputDirectory' or " +
          "local-name()='testIncludes' or " +
          "local-name()='testExcludes' or " +
          "local-name()='includes' or " +
          "local-name()='excludes']"),
        $conformanceBuild.SelectNodes(
          "./*[local-name()='plugins']/*[local-name()='plugin']" +
          "[normalize-space(*[local-name()='artifactId'])=" +
          "'build-helper-maven-plugin']" +
          "//*[local-name()='goal' and " +
          "normalize-space(.)='add-test-source']"),
        $conformanceBuild.SelectNodes(
          "./*[local-name()='plugins']/*[local-name()='plugin']" +
          "[normalize-space(*[local-name()='artifactId'])=" +
          "'maven-resources-plugin']" +
          "//*[local-name()='goal' and " +
          "normalize-space(.)='copy-resources']")
      ) | ForEach-Object { @($_) }
      if (@($unsupportedTestPathNodes).Count -ne 0) {
        $errors.Add(@'
The activated multiclouddb-conformance build contains unsupported test
source, compiler-selection, output, or classpath substitutions. The starter
supports only Maven's canonical test source/output path so the source contract
is bound to the class selected by Surefire.
'@
        )
      }

      $forbiddenSystemPropertyNames = @(
        'cosmos.key',
        'cosmos.endpoint',
        'COSMOS_KEY',
        'COSMOS_ENDPOINT',
        'jdk.tls.disabledAlgorithms',
        'jdk.certpath.disabledAlgorithms',
        'java.security.properties',
        'AZURE_TOKEN_CREDENTIALS',
        'AZURE_CLIENT_SECRET',
        'AZURE_CLIENT_CERTIFICATE_PATH',
        'AZURE_CLIENT_CERTIFICATE_PASSWORD'
      )
      $forbiddenEnvironmentNames = @(
        'cosmos.key',
        'cosmos.endpoint',
        'COSMOS_KEY',
        'COSMOS_ENDPOINT',
        'AZURE_TOKEN_CREDENTIALS',
        'AZURE_CONFIG_DIR',
        'AZURE_CLIENT_SECRET',
        'AZURE_CLIENT_CERTIFICATE_PATH',
        'AZURE_CLIENT_CERTIFICATE_PASSWORD'
      )
      $requiredInheritedEnvironmentNames = @(
        'AZURE_TOKEN_CREDENTIALS',
        'AZURE_CONFIG_DIR'
      )
      $injections = [System.Collections.Generic.List[string]]::new()
      foreach ($plugin in $surefire) {
        if (@($plugin.SelectNodes(
            ".//*[local-name()='systemPropertiesFile']")).Count -ne 0) {
          $injections.Add('systemPropertiesFile')
        }
        foreach ($containerName in @(
            'systemPropertyVariables',
            'systemProperties')) {
          foreach ($container in @($plugin.SelectNodes(
              ".//*[local-name()='$containerName']"))) {
            foreach ($node in @($container.SelectNodes('.//*'))) {
              $names = @($node.LocalName)
              if ($node.LocalName -eq 'property') {
                $names += @($node.SelectNodes("./*[local-name()='name']") |
                  ForEach-Object { $_.InnerText })
              }
              foreach ($name in $names) {
                if ($forbiddenSystemPropertyNames -contains $name) {
                  $injections.Add("$containerName/$name")
                }
              }
            }
          }
        }

        foreach ($container in @($plugin.SelectNodes(
            ".//*[local-name()='environmentVariables']"))) {
          foreach ($entry in @($container.SelectNodes('./*'))) {
            $entryNames = @($entry.LocalName)
            if ($entry.LocalName -eq 'property') {
              $entryNames += @($entry.SelectNodes(
                "./*[local-name()='name']") |
                ForEach-Object { $_.InnerText })
            }
            foreach ($entryName in $entryNames) {
              if ($forbiddenEnvironmentNames -contains $entryName) {
                $injections.Add(
                  "environmentVariables/$entryName")
              }
              if ($forbiddenOptionVariables -contains $entryName `
                  -and $entry.InnerText -match
                    $forbiddenNestedJvmConfigPattern) {
                $injections.Add(
                  "environmentVariables/$entryName content")
              }
            }
          }
        }

        foreach ($container in @($plugin.SelectNodes(
            ".//*[local-name()='excludedEnvironmentVariables']"))) {
          $excludedElements = @($container.SelectNodes('./*'))
          $rawExcludedNames = if ($excludedElements.Count -ne 0) {
            @($excludedElements |
              ForEach-Object { $_.InnerText })
          } else {
            @($container.InnerText)
          }
          foreach ($rawExcludedName in $rawExcludedNames) {
            foreach ($excludedName in @(
                $rawExcludedName -split '[,\s]+' |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) })) {
              if ($requiredInheritedEnvironmentNames -contains
                  $excludedName) {
                $injections.Add(
                  "excludedEnvironmentVariables/$excludedName")
              }
            }
          }
        }

        foreach ($argLine in @($plugin.SelectNodes(
            ".//*[local-name()='argLine']"))) {
          if ($argLine.InnerText -match
              $forbiddenJvmPropertyPattern) {
            $injections.Add(
              'argLine/forbidden JVM property')
          }
          if ($argLine.InnerText -match
              $forbiddenJvmArgumentIndirectionPattern `
              -or $argLine.InnerText -match
                $forbiddenJvmExecutableOptionPattern) {
            $injections.Add(
              'argLine/unsupported JVM argument indirection or executable option')
          }
        }
        foreach ($executionOverride in @(
            $plugin.SelectNodes(
              ".//*[local-name()='jvm' or " +
              "local-name()='debugForkedProcess']"))) {
          if (-not [string]::IsNullOrWhiteSpace(
              $executionOverride.InnerText)) {
            $injections.Add(
              "$($executionOverride.LocalName)/unsupported Surefire JVM execution override")
          }
        }
      }

      if ($injections.Count -ne 0) {
        $injectionList = ($injections | Sort-Object -Unique) -join ', '
        $errors.Add(@"
The activated live-cosmos effective Surefire configuration contains forbidden
emulator/key/endpoint/security-policy or identity-routing injection channels:
$injectionList. The test JVM must inherit AZURE_TOKEN_CREDENTIALS=
AzureCliCredential and AZURE_CONFIG_DIR from the protected AzureCLI task;
Surefire must not override or exclude either variable and must not inject
client-secret or client-certificate credential variables.
multiclouddb-conformance/pom.xml currently declares emulator endpoint/key
injection in its own build/plugins, which a root-POM profile cannot override.
Declare live-cosmos in multiclouddb-conformance/pom.xml and replace that
configuration with combine.self='override', or safely relocate emulator injection.
"@
        )
      }
    }
  }
}
}

if ($errors.Count -eq 0) {
  $manifestJson = ConvertTo-Json `
    -InputObject ([object[]]$liveMavenArguments) -Compress
  [System.IO.File]::WriteAllText(
    $argumentManifest,
    $manifestJson,
    [System.Text.UTF8Encoding]::new($false))
  if (-not (Test-Path -LiteralPath $argumentManifest -PathType Leaf)) {
    $errors.Add(
      'The validated Maven argument manifest was not written.')
  }
}

if ($errors.Count -gt 0) {
  foreach ($message in $errors) {
    Write-Host "##vso[task.logissue type=error]$($message -replace '\r?\n', ' ')"
  }
  throw "Live Cosmos DB preflight failed with $($errors.Count) configuration error(s)."
}
