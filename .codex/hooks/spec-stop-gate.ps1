$ErrorActionPreference = "Stop"

# Lifecycle hooks cannot invoke an interactive skill directly, so this script
# implements the repo's `spec-self-eval` skill procedure against the same
# checklist and report contract.

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Get-StateDir {
    $stateDir = Join-Path (Get-RepoRoot) ".codex-hooks-state"
    if (-not (Test-Path $stateDir)) {
        New-Item -ItemType Directory -Path $stateDir | Out-Null
    }
    return $stateDir
}

function Get-HookInput {
    $raw = [Console]::In.ReadToEnd()
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return $null
    }

    try {
        return $raw | ConvertFrom-Json
    } catch {
        return $null
    }
}

function Get-InputValue {
    param(
        $InputObject,
        [string[]]$Names,
        [string]$DefaultValue = ""
    )

    if ($null -eq $InputObject) {
        return $DefaultValue
    }

    foreach ($name in $Names) {
        $property = $InputObject.PSObject.Properties[$name]
        if ($property -and $null -ne $property.Value -and "$($property.Value)" -ne "") {
            return "$($property.Value)"
        }
    }

    return $DefaultValue
}

function Get-SnapshotPath {
    param([string]$SessionKey)
    return (Join-Path (Get-StateDir) ("spec-turn-{0}.json" -f $SessionKey))
}

function Normalize-RelPath {
    param(
        [string]$RepoRoot,
        [string]$FullPath
    )

    $rootPath = (Resolve-Path $RepoRoot).Path.TrimEnd("\")
    $resolvedPath = (Resolve-Path $FullPath).Path
    if ($resolvedPath.StartsWith($rootPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        $relative = $resolvedPath.Substring($rootPath.Length).TrimStart("\")
    } else {
        $relative = $resolvedPath
    }
    return ($relative -replace "\\", "/")
}

function Get-SpecFiles {
    param([string]$RepoRoot)

    $specRoot = Join-Path $RepoRoot ".specs"
    if (-not (Test-Path $specRoot)) {
        return @()
    }

    return Get-ChildItem -Path $specRoot -Recurse -File | Where-Object {
        $rel = Normalize-RelPath -RepoRoot $RepoRoot -FullPath $_.FullName
        $rel -match '^\.specs/([^/_][^/]*)/.+'
    }
}

function Get-SpecState {
    param([string]$RepoRoot)

    $state = @{}
    foreach ($file in Get-SpecFiles -RepoRoot $RepoRoot) {
        $rel = Normalize-RelPath -RepoRoot $RepoRoot -FullPath $file.FullName
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
        $state[$rel] = $hash
    }
    return $state
}

function Save-Snapshot {
    param(
        [string]$RepoRoot,
        [string]$SessionKey
    )

    $payload = @{
        repoRoot = $RepoRoot
        capturedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        files = Get-SpecState -RepoRoot $RepoRoot
    }

    $json = $payload | ConvertTo-Json -Depth 6
    [System.IO.File]::WriteAllText((Get-SnapshotPath -SessionKey $SessionKey), $json)
}

function Load-Snapshot {
    param([string]$SessionKey)

    $snapshotPath = Get-SnapshotPath -SessionKey $SessionKey
    if (-not (Test-Path $snapshotPath)) {
        return $null
    }

    return (Get-Content -Raw $snapshotPath | ConvertFrom-Json)
}

function Remove-Snapshot {
    param([string]$SessionKey)

    $snapshotPath = Get-SnapshotPath -SessionKey $SessionKey
    if (Test-Path $snapshotPath) {
        Remove-Item -LiteralPath $snapshotPath -Force
    }
}

function Get-TouchedFeatures {
    param(
        [string]$RepoRoot,
        $Snapshot
    )

    $before = @{}
    if ($Snapshot -and $Snapshot.PSObject.Properties["files"] -and $Snapshot.files) {
        foreach ($property in $Snapshot.files.PSObject.Properties) {
            $before[$property.Name] = "$($property.Value)"
        }
    }

    $after = Get-SpecState -RepoRoot $RepoRoot
    $changed = New-Object System.Collections.Generic.HashSet[string]

    foreach ($path in $before.Keys) {
        if (-not $after.ContainsKey($path) -or $after[$path] -ne $before[$path]) {
            $null = $changed.Add($path)
        }
    }

    foreach ($path in $after.Keys) {
        if (-not $before.ContainsKey($path)) {
            $null = $changed.Add($path)
        }
    }

    $features = New-Object System.Collections.Generic.HashSet[string]
    foreach ($path in $changed) {
        if ($path -match '^\.specs/([^/_][^/]*)/') {
            $null = $features.Add($matches[1])
        }
    }

    return @($features | Sort-Object)
}

function Get-MarkdownHeadings {
    param([string]$Content)

    $headings = @()
    $lines = $Content -split "`r?`n"
    foreach ($line in $lines) {
        if ($line -match '^(#+)\s+(.+?)\s*$') {
            $headings += $matches[2]
        }
    }
    return $headings
}

function Get-GithubAnchor {
    param([string]$Heading)

    $anchor = $Heading.ToLowerInvariant()
    $anchor = $anchor -replace '`', ''
    $anchor = $anchor -replace '[^a-z0-9\u0400-\u04ff\s-]', ''
    $anchor = $anchor -replace '\s+', '-'
    $anchor = $anchor -replace '-+', '-'
    $anchor = $anchor.Trim('-')
    return $anchor
}

function Get-AcceptanceCriteria {
    param([string]$RequirementsContent)

    $lines = $RequirementsContent -split "`r?`n"
    $criteria = @()
    $currentStory = 0
    $inAcceptanceCriteria = $false
    $currentStoryHeading = ""

    foreach ($line in $lines) {
        if ($line -match '^###\s+User Story\s+(\d+):\s+(.+)$') {
            $currentStory = [int]$matches[1]
            $currentStoryHeading = $matches[2]
            $inAcceptanceCriteria = $false
            continue
        }

        if ($line -match '^####\s+Acceptance Criteria') {
            $inAcceptanceCriteria = $true
            continue
        }

        if ($line -match '^###\s+') {
            $inAcceptanceCriteria = $false
        }

        if ($inAcceptanceCriteria -and $line -match '^\s*(\d+)\.\s+(.+?)\s*$') {
            $criteria += @{
                id = "US{0}.AC{1}" -f $currentStory, $matches[1]
                story = $currentStory
                storyHeading = $currentStoryHeading
                text = $matches[2].Trim()
            }
        }
    }

    return $criteria
}

function Get-TaskBlocks {
    param([string]$TasksContent)

    $tasks = @()
    $lines = $TasksContent -split "`r?`n"
    $current = $null
    $section = ""

    foreach ($line in $lines) {
        if ($line -match '^##\s+(TASK-\d+):\s+(.+)$') {
            if ($current) {
                $tasks += $current
            }
            $current = @{
                id = $matches[1]
                title = $matches[2]
                refs = @()
                dod = @()
                dependencies = @()
            }
            $section = ""
            continue
        }

        if (-not $current) {
            continue
        }

        if ($line -match '^###\s+Refs') {
            $section = "refs"
            continue
        }

        if ($line -match '^###\s+DoD') {
            $section = "dod"
            continue
        }

        if ($line -match '^###\s+Dependencies') {
            $section = "dependencies"
            continue
        }

        if ($line -match '^---\s*$') {
            continue
        }

        if ($section -eq "refs" -and $line -match '^\s*-\s+\[(.+?)\]\((.+?)\)\s*$') {
            $current.refs += @{
                label = $matches[1]
                target = $matches[2]
            }
            continue
        }

        if ($section -eq "dod" -and $line -match '^\s*-\s+\[( |x)\]\s+(.+?)\s*$') {
            $current.dod += $matches[2].Trim()
            continue
        }

        if ($section -eq "dependencies" -and $line -match '^\s*-\s+(.+?)\s*$') {
            $current.dependencies += $matches[1].Trim()
            continue
        }

        if ($section -eq "dependencies" -and $line -match '^\s*None\.\s*$') {
            $current.dependencies += "None."
            continue
        }
    }

    if ($current) {
        $tasks += $current
    }

    return $tasks
}

function Test-EarsForm {
    param([string]$Text)

    return $Text -match '^(The .+ shall .+|When .+, .+ shall .+|If .+, .+ shall .+|While .+, .+ shall .+|Where .+, .+ shall .+)$'
}

function Resolve-ChecklistPath {
    param([string]$RepoRoot)

    $repoChecklist = Join-Path $RepoRoot ".specs/_eval-checklist.md"
    if (Test-Path $repoChecklist) {
        return $repoChecklist
    }

    $fallback = Join-Path $RepoRoot ".codex/skills/spec-self-eval/references/_eval-checklist.md"
    if (Test-Path $fallback) {
        return $fallback
    }

    throw "spec-self-eval checklist not found"
}

function Resolve-MarkdownLink {
    param(
        [string]$RepoRoot,
        [string]$FeatureDir,
        [string]$SourceFile,
        [string]$Target
    )

    if ($Target -match '^https?://') {
        return $true
    }

    $parts = $Target.Split('#', 2)
    $relativePath = $parts[0]
    $fragment = if ($parts.Count -gt 1) { $parts[1] } else { "" }

    $targetPath = if ([string]::IsNullOrWhiteSpace($relativePath)) {
        $SourceFile
    } else {
        Join-Path (Split-Path $SourceFile -Parent) $relativePath
    }
    $targetPath = (Resolve-Path -LiteralPath $targetPath -ErrorAction SilentlyContinue)
    if (-not $targetPath) {
        return $false
    }

    if ([string]::IsNullOrWhiteSpace($fragment)) {
        return $true
    }

    $content = Get-Content -Raw -LiteralPath $targetPath
    $anchors = @((Get-MarkdownHeadings -Content $content | ForEach-Object { Get-GithubAnchor -Heading $_ }))
    return $anchors -contains $fragment.ToLowerInvariant()
}

function Get-TitleFromHeading {
    param(
        [string]$Content,
        [string]$Fallback
    )

    $first = ($Content -split "`r?`n" | Where-Object { $_ -match '^#\s+' } | Select-Object -First 1)
    if (-not $first) {
        return $Fallback
    }

    return ($first -replace '^#\s+', '' -replace '^(Requirements|Design|Tasks):\s*', '').Trim()
}

function New-Finding {
    param(
        [int]$Index,
        [string]$Criterion,
        [string]$Verdict,
        [string]$Evidence,
        [string[]]$Details
    )

    return @{
        index = $Index
        criterion = $Criterion
        verdict = $Verdict
        evidence = $Evidence
        details = $Details
    }
}

function Invoke-SpecSelfEval {
    param(
        [string]$RepoRoot,
        [string]$Feature
    )

    $featureDir = Join-Path $RepoRoot (".specs/{0}" -f $Feature)
    $requirementsPath = Join-Path $featureDir "requirements.md"
    $designPath = Join-Path $featureDir "design.md"
    $tasksPath = Join-Path $featureDir "tasks.md"

    foreach ($requiredPath in @($requirementsPath, $designPath, $tasksPath)) {
        if (-not (Test-Path $requiredPath)) {
            throw "Missing spec file: $requiredPath"
        }
    }

    $checklistPath = Resolve-ChecklistPath -RepoRoot $RepoRoot
    $checklistContent = Get-Content -Raw -LiteralPath $checklistPath
    $criteriaText = @()
    foreach ($line in ($checklistContent -split "`r?`n")) {
        if ($line -match '^\s*-\s+(.+?)\.\s*$') {
            $criteriaText += $matches[1].Trim()
        }
    }

    $requirements = Get-Content -Raw -LiteralPath $requirementsPath
    $design = Get-Content -Raw -LiteralPath $designPath
    $tasks = Get-Content -Raw -LiteralPath $tasksPath

    $acs = Get-AcceptanceCriteria -RequirementsContent $requirements
    $taskBlocks = Get-TaskBlocks -TasksContent $tasks
    $designHeadings = Get-MarkdownHeadings -Content $design

    $findings = @()

    $vaguePattern = '\b(appropriate|sufficient|easy|quick|fast|securely|properly|efficiently|robust)\b'
    if ($acs.Count -eq 0) {
        $findings += New-Finding -Index 1 -Criterion $criteriaText[0] -Verdict "FAIL" -Evidence "[FAIL] requirements.md has no detectable acceptance criteria blocks." -Details @(
            "Add numbered ACs under each `#### Acceptance Criteria` section in `requirements.md`."
        )
    } elseif (($acs | Where-Object { $_.text -match $vaguePattern }).Count -gt 0) {
        $weakAcs = ($acs | Where-Object { $_.text -match $vaguePattern } | ForEach-Object { $_.id }) -join ", "
        $findings += New-Finding -Index 1 -Criterion $criteriaText[0] -Verdict "WEAK" -Evidence "[WEAK] requirements.md ACs exist, but $weakAcs include vague wording that may weaken testability." -Details @(
            "Replace vague terms with observable API behavior, bounded values, or explicit status/field checks."
        )
    } else {
        $findings += New-Finding -Index 1 -Criterion $criteriaText[0] -Verdict "PASS" -Evidence "[PASS] requirements.md defines $($acs.Count) numbered ACs with observable endpoint, filter, ordering, pagination, or validation behavior." -Details @()
    }

    $tasksMissingRefs = @($taskBlocks | Where-Object { $_.refs.Count -eq 0 } | ForEach-Object { $_.id })
    $tasksMissingDod = @($taskBlocks | Where-Object { $_.dod.Count -eq 0 } | ForEach-Object { $_.id })
    if ($taskBlocks.Count -eq 0) {
        $findings += New-Finding -Index 2 -Criterion $criteriaText[1] -Verdict "FAIL" -Evidence "[FAIL] tasks.md has no detectable TASK sections." -Details @(
            "Add `## TASK-n:` sections with `### Refs` and `### DoD` blocks."
        )
    } elseif ($tasksMissingRefs.Count -gt 0 -or $tasksMissingDod.Count -gt 0) {
        $details = @()
        if ($tasksMissingRefs.Count -gt 0) {
            $details += "Missing `### Refs`: $($tasksMissingRefs -join ', ')."
        }
        if ($tasksMissingDod.Count -gt 0) {
            $details += "Missing `### DoD`: $($tasksMissingDod -join ', ')."
        }
        $findings += New-Finding -Index 2 -Criterion $criteriaText[1] -Verdict "FAIL" -Evidence "[FAIL] tasks.md task blocks are incomplete." -Details $details
    } else {
        $findings += New-Finding -Index 2 -Criterion $criteriaText[1] -Verdict "PASS" -Evidence "[PASS] every TASK section in tasks.md includes both `### Refs` and `### DoD`." -Details @()
    }

    $tasksMissingDeps = @($taskBlocks | Where-Object { $_.dependencies.Count -eq 0 } | ForEach-Object { $_.id })
    if ($taskBlocks.Count -eq 0) {
        $findings += New-Finding -Index 3 -Criterion $criteriaText[2] -Verdict "FAIL" -Evidence "[FAIL] tasks.md has no detectable TASK sections to carry dependencies." -Details @(
            "Add `### Dependencies` to each task, using `None.` when there is no prerequisite."
        )
    } elseif ($tasksMissingDeps.Count -gt 0) {
        $findings += New-Finding -Index 3 -Criterion $criteriaText[2] -Verdict "FAIL" -Evidence "[FAIL] tasks.md omits dependency blocks for: $($tasksMissingDeps -join ', ')." -Details @(
            "Every task needs an explicit `### Dependencies` block, even if the value is `None.`."
        )
    } else {
        $findings += New-Finding -Index 3 -Criterion $criteriaText[2] -Verdict "PASS" -Evidence "[PASS] every TASK section in tasks.md includes an explicit `### Dependencies` block." -Details @()
    }

    $unmappedAcs = @()
    foreach ($ac in $acs) {
        $tokens = ($ac.text.ToLowerInvariant() -replace '[^a-z0-9\s-]', ' ') -split '\s+' | Where-Object { $_.Length -ge 4 }
        $matched = $false
        foreach ($heading in $designHeadings) {
            $headingText = $heading.ToLowerInvariant()
            $score = ($tokens | Where-Object { $headingText -like "*$_*" }).Count
            if ($score -ge 2) {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            $unmappedAcs += $ac.id
        }
    }

    if ($designHeadings.Count -eq 0) {
        $findings += New-Finding -Index 4 -Criterion $criteriaText[3] -Verdict "FAIL" -Evidence "[FAIL] design.md has no detectable headings to map against requirements ACs." -Details @(
            "Add explicit design sections that cover the acceptance criteria."
        )
    } elseif ($unmappedAcs.Count -eq 0) {
        $findings += New-Finding -Index 4 -Criterion $criteriaText[3] -Verdict "PASS" -Evidence "[PASS] each AC has at least one keyword-level match in design.md headings or sections." -Details @()
    } else {
        $findings += New-Finding -Index 4 -Criterion $criteriaText[3] -Verdict "WEAK" -Evidence "[WEAK] most ACs map into design.md, but these need clearer section coverage: $($unmappedAcs -join ', ')." -Details @(
            "Add or rename design sections so each AC has an obvious home."
        )
    }

    $taskRefsText = ($taskBlocks | ForEach-Object { $_.refs.target }) -join "`n"
    if ($taskBlocks.Count -eq 0) {
        $findings += New-Finding -Index 5 -Criterion $criteriaText[4] -Verdict "FAIL" -Evidence "[FAIL] tasks.md has no TASK sections to trace ACs into implementation work." -Details @(
            "Add tasks with requirement refs and DoD items."
        )
    } elseif ($taskRefsText -notmatch 'requirements\.md#') {
        $findings += New-Finding -Index 5 -Criterion $criteriaText[4] -Verdict "FAIL" -Evidence "[FAIL] tasks.md has no `requirements.md#...` references, so AC-to-task traceability is absent." -Details @(
            "Reference user stories or individual AC anchors from each relevant task."
        )
    } elseif ($taskRefsText -notmatch 'ac') {
        $findings += New-Finding -Index 5 -Criterion $criteriaText[4] -Verdict "WEAK" -Evidence "[WEAK] tasks.md traces back to requirement sections, but most refs stop at user-story level rather than specific AC anchors." -Details @(
            "Tighten refs or DoD wording so each AC is obviously protected by one or more tasks."
        )
    } else {
        $findings += New-Finding -Index 5 -Criterion $criteriaText[4] -Verdict "PASS" -Evidence "[PASS] tasks.md includes requirement-anchor refs alongside DoD blocks, providing explicit AC-to-task traceability." -Details @()
    }

    $facts = @{
        sort = @()
        limitMax = @()
        limitDefault = @()
        offsetDefault = @()
        contextField = @()
    }

    foreach ($entry in @(
        @{ name = "requirements.md"; text = $requirements },
        @{ name = "design.md"; text = $design },
        @{ name = "tasks.md"; text = $tasks }
    )) {
        if ($entry.text -match 'occurredAt DESC,\s*id DESC|timestamp DESC,\s*id DESC') {
            $facts.sort += $entry.name
        }
        if ($entry.text -match 'maximum allowed `limit` is `(\d+)`|maximum `(\d+)`|outside `\[1,\s*(\d+)\]`') {
            $value = @($matches[1], $matches[2], $matches[3] | Where-Object { $_ })[0]
            $facts.limitMax += "{0}:{1}" -f $entry.name, $value
        }
        if ($entry.text -match 'Default page size is `(\d+)`|Default `limit` is `(\d+)`|default `(\d+)`') {
            $value = @($matches[1], $matches[2], $matches[3] | Where-Object { $_ })[0]
            if ($value) {
                $facts.limitDefault += "{0}:{1}" -f $entry.name, $value
            }
        }
        if ($entry.text -match 'Default `offset` is `(\d+)`|offset` is `(\d+)`') {
            $value = @($matches[1], $matches[2] | Where-Object { $_ })[0]
            if ($value) {
                $facts.offsetDefault += "{0}:{1}" -f $entry.name, $value
            }
        }
        if ($entry.text -match '\bcontext\b' -and $entry.text -match '\bpayload\b') {
            $facts.contextField += $entry.name
        }
    }

    $contradictions = @()
    if ($facts.sort.Count -lt 2) {
        $contradictions += "Canonical sort order is not consistently stated across the three spec files."
    }
    if (($facts.limitMax | ForEach-Object { ($_ -split ':', 2)[1] } | Select-Object -Unique).Count -gt 1) {
        $contradictions += "Maximum `limit` value differs between files: $($facts.limitMax -join ', ')."
    }
    if (($facts.limitDefault | ForEach-Object { ($_ -split ':', 2)[1] } | Select-Object -Unique).Count -gt 1) {
        $contradictions += "Default `limit` value differs between files: $($facts.limitDefault -join ', ')."
    }

    if (($facts.limitMax | Measure-Object).Count -eq 0 -or ($facts.limitDefault | Measure-Object).Count -eq 0 -or $facts.sort.Count -lt 2) {
        $findings += New-Finding -Index 6 -Criterion $criteriaText[5] -Verdict "WEAK" -Evidence "[WEAK] no direct contradiction was found, but some shared normative facts are not restated consistently enough to prove full alignment." -Details @(
            "Repeat key invariants such as sort order and pagination bounds wherever downstream tasks depend on them."
        )
    } elseif ($contradictions.Count -gt 0) {
        $findings += New-Finding -Index 6 -Criterion $criteriaText[5] -Verdict "FAIL" -Evidence "[FAIL] contradictory or missing normative facts were detected across requirements/design/tasks." -Details $contradictions
    } else {
        $findings += New-Finding -Index 6 -Criterion $criteriaText[5] -Verdict "PASS" -Evidence "[PASS] shared facts such as sort order and pagination bounds are stated consistently across the three spec files." -Details @()
    }

    $brokenRefs = @()
    foreach ($sourceFile in @($requirementsPath, $designPath, $tasksPath)) {
        $content = Get-Content -Raw -LiteralPath $sourceFile
        foreach ($match in [regex]::Matches($content, '\[[^\]]+\]\(([^)]+)\)')) {
            $target = $match.Groups[1].Value
            if (-not (Resolve-MarkdownLink -RepoRoot $RepoRoot -FeatureDir $featureDir -SourceFile $sourceFile -Target $target)) {
                $brokenRefs += "{0} -> {1}" -f ([System.IO.Path]::GetFileName($sourceFile)), $target
            }
        }
    }

    if ($brokenRefs.Count -gt 0) {
        $findings += New-Finding -Index 7 -Criterion $criteriaText[6] -Verdict "FAIL" -Evidence "[FAIL] markdown cross-references do not resolve cleanly." -Details $brokenRefs
    } else {
        $findings += New-Finding -Index 7 -Criterion $criteriaText[6] -Verdict "PASS" -Evidence "[PASS] markdown cross-references in requirements.md, design.md, and tasks.md resolve to real files and anchors." -Details @()
    }

    $earsOk = @($acs | Where-Object { Test-EarsForm -Text $_.text })
    if ($acs.Count -eq 0) {
        $findings += New-Finding -Index 8 -Criterion $criteriaText[7] -Verdict "FAIL" -Evidence "[FAIL] requirements.md has no ACs to validate against EARS form." -Details @(
            "Add acceptance criteria in EARS form."
        )
    } elseif ($earsOk.Count -eq $acs.Count) {
        $findings += New-Finding -Index 8 -Criterion $criteriaText[7] -Verdict "PASS" -Evidence "[PASS] all $($acs.Count) ACs match a basic EARS pattern." -Details @()
    } elseif ($earsOk.Count -eq 0) {
        $findings += New-Finding -Index 8 -Criterion $criteriaText[7] -Verdict "FAIL" -Evidence "[FAIL] none of the detected ACs use a recognizable EARS form such as `The ... shall ...` or `When ... shall ...`." -Details @(
            "Rewrite ACs into Ubiquitous / Event-driven / Unwanted / State-driven / Optional EARS style."
        )
    } else {
        $nonEars = @($acs | Where-Object { -not (Test-EarsForm -Text $_.text) } | ForEach-Object { $_.id })
        $findings += New-Finding -Index 8 -Criterion $criteriaText[7] -Verdict "WEAK" -Evidence "[WEAK] $($earsOk.Count)/$($acs.Count) ACs match EARS; remaining non-EARS ACs: $($nonEars -join ', ')." -Details @(
            "Reword the non-EARS ACs into explicit `shall` statements."
        )
    }

    $today = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd")
    $reportPath = Join-Path $featureDir ("eval-report-{0}.md" -f $today)
    $title = Get-TitleFromHeading -Content $requirements -Fallback $Feature
    $checklistDisplay = Normalize-RelPath -RepoRoot $RepoRoot -FullPath $checklistPath

    $passCount = @($findings | Where-Object { $_.verdict -eq "PASS" }).Count
    $weakCount = @($findings | Where-Object { $_.verdict -eq "WEAK" }).Count
    $failCount = @($findings | Where-Object { $_.verdict -eq "FAIL" }).Count

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# Specs Self-Evaluation Report: $title")
    $lines.Add("")
    $lines.Add("- **Date:** $today")
    $lines.Add(('- **Scope:** `.specs/{0}/{{requirements.md, design.md, tasks.md}}`' -f $Feature))
    $lines.Add(('- **Checklist:** `{0}`' -f $checklistDisplay))
    $lines.Add("")
    $lines.Add("## Summary")
    $lines.Add("")
    $lines.Add("| # | Criterion | Verdict |")
    $lines.Add("|---|---|---|")
    foreach ($finding in $findings) {
        $lines.Add("| $($finding.index) | $($finding.criterion) | $($finding.verdict) |")
    }
    $lines.Add("")
    $lines.Add("**Totals:** PASS: $passCount | WEAK: $weakCount | FAIL: $failCount")
    $lines.Add("")
    $lines.Add("## Findings")
    $lines.Add("")
    foreach ($finding in $findings) {
        $lines.Add("### $($finding.index). $($finding.criterion) - [$($finding.verdict)]")
        $lines.Add($finding.evidence)
        foreach ($detail in $finding.details) {
            $lines.Add("- $detail")
        }
        $lines.Add("")
    }
    $lines.Add("## Recommended next steps")
    $lines.Add("")
    if ($failCount -gt 0) {
        foreach ($finding in ($findings | Where-Object { $_.verdict -eq "FAIL" })) {
            $lines.Add("- Fix criterion $($finding.index): $($finding.criterion).")
        }
    } elseif ($weakCount -gt 0) {
        foreach ($finding in ($findings | Where-Object { $_.verdict -eq "WEAK" })) {
            $lines.Add("- Strengthen criterion $($finding.index): $($finding.criterion).")
        }
    } else {
        $lines.Add("- No blocking issues found. Keep the spec and tasks in sync as implementation evolves.")
    }

    [System.IO.File]::WriteAllLines($reportPath, $lines)

    return @{
        feature = $Feature
        reportPath = $reportPath
        findings = $findings
        failCount = $failCount
        weakCount = $weakCount
        passCount = $passCount
    }
}

$inputData = Get-HookInput
$eventName = Get-InputValue -InputObject $inputData -Names @("hook_event_name", "hookEventName")
$sessionKey = Get-InputValue -InputObject $inputData -Names @("session_id", "sessionId", "thread_id", "threadId") -DefaultValue "default"
$repoRoot = Get-RepoRoot

if ($eventName -eq "UserPromptSubmit") {
    Save-Snapshot -RepoRoot $repoRoot -SessionKey $sessionKey
    exit 0
}

if ($eventName -ne "Stop") {
    exit 0
}

$snapshot = Load-Snapshot -SessionKey $sessionKey
if (-not $snapshot) {
    exit 0
}

$touchedFeatures = Get-TouchedFeatures -RepoRoot $repoRoot -Snapshot $snapshot
if ($touchedFeatures.Count -eq 0) {
    Remove-Snapshot -SessionKey $sessionKey
    exit 0
}

$results = @()
foreach ($feature in $touchedFeatures) {
    try {
        $results += Invoke-SpecSelfEval -RepoRoot $repoRoot -Feature $feature
    } catch {
        $message = ('spec-self-eval hook failed for `.specs/{0}`: {1}' -f $feature, $_.Exception.Message)
        [Console]::Error.WriteLine($message)
        exit 2
    }
}

$blocking = @()
foreach ($result in $results) {
    foreach ($finding in ($result.findings | Where-Object { $_.verdict -eq "FAIL" })) {
        $blocking += "- $($result.feature): [$($finding.index)] $($finding.criterion) -> $(Normalize-RelPath -RepoRoot $repoRoot -FullPath $result.reportPath)"
    }
}

if ($blocking.Count -gt 0) {
    $prompt = @(
        "Spec self-eval failed for the feature specs you changed. Fix the blocking items before ending the turn.",
        "",
        "Blocking items:",
        ($blocking -join "`n"),
        "",
        "After fixing the spec files under `.specs/<feature>/`, try ending the turn again."
    ) -join "`n"

    [Console]::Error.WriteLine($prompt)
    exit 2
}

Remove-Snapshot -SessionKey $sessionKey
exit 0
