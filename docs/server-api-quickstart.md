# 서버 API 실습

## API 분석 작업을 요청하고 이력 조회하기

아래 예시는 **자신의 로컬 서버**에서 실행하는 PowerShell 코드입니다. 별도 모듈 없이 실행할 수 있으며, 코드 블록을 순서대로 같은 PowerShell 세션에서 실행합니다.

한 사용자 계정에 프론트엔드·백엔드 Agent를 등록해 서버 API 흐름을 확인합니다. 실제 팀에서는 각 사용자가 자신의 Agent를 연결합니다. 예시는 새로운 사용자·팀·Agent·작업을 로컬 DB에 생성합니다.

### 1. 회원가입·로그인과 팀 생성

사용자 API는 세션 쿠키와 CSRF 토큰이 필요합니다. 아래 함수는 쿠키를 유지하고 변경 요청에 CSRF 토큰을 붙입니다.

```powershell
$apiBase = 'http://localhost:8080/api/v1'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$csrf = Invoke-RestMethod -Uri "$apiBase/auth/csrf" -WebSession $session

function Invoke-DevLinkUserApi {
    param([string]$Method, [string]$Path, [object]$Body = $null)

    $requestParams = @{
        Uri = "$apiBase$Path"
        Method = $Method
        WebSession = $session
        ErrorAction = 'Stop'
    }
    if ($Method -notin @('GET', 'HEAD', 'OPTIONS')) {
        $requestParams.Headers = @{ 'X-CSRF-TOKEN' = $csrf.data.token }
    }
    if ($null -ne $Body) {
        $requestParams.ContentType = 'application/json; charset=utf-8'
        $requestParams.Body = [System.Text.Encoding]::UTF8.GetBytes(
            ($Body | ConvertTo-Json -Depth 10)
        )
    }
    Invoke-RestMethod @requestParams
}

$demoEmail = 'demo-' + [guid]::NewGuid().ToString('N') + '@example.com'
$demoPassword = [guid]::NewGuid().ToString('N') + '!aA1'

$null = Invoke-DevLinkUserApi POST '/auth/signup' @{
    email = $demoEmail
    password = $demoPassword
    name = 'DevLink Demo'
}
$null = Invoke-DevLinkUserApi POST '/auth/login' @{
    email = $demoEmail
    password = $demoPassword
}

# 로그인 후 CSRF 토큰을 다시 조회합니다.
$csrf = Invoke-RestMethod -Uri "$apiBase/auth/csrf" -WebSession $session
$teamReply = Invoke-DevLinkUserApi POST '/teams' @{ name = 'Demo Team' }
$teamId = $teamReply.data.teamId
```

### 2. Agent 등록과 작업 요청

```powershell
$frontReply = Invoke-DevLinkUserApi POST "/agents/teams/$teamId" @{
    name = 'Frontend Agent'
    toolType = 'CODEX'
    projectName = 'Demo App'
    part = 'FRONTEND'
    roles = @('frontend', 'react')
}
$backReply = Invoke-DevLinkUserApi POST "/agents/teams/$teamId" @{
    name = 'Backend Agent'
    toolType = 'CLAUDE_CODE'
    projectName = 'Demo App'
    part = 'BACKEND'
    roles = @('backend', 'api', 'spring')
}
$frontAgentId = $frontReply.data.agentId
$backAgentId = $backReply.data.agentId

$tokenReply = Invoke-DevLinkUserApi POST "/agents/$frontAgentId/token"
$agentHeaders = @{ Authorization = "Bearer $($tokenReply.data.token)" }
$taskBody = @{
    requestKey = [guid]::NewGuid().ToString()
    receiverAgentId = $backAgentId
    taskSummary = '게시글 목록 API 응답 지연 분석'
    prompt = '게시글 목록 조회가 느립니다. 관련 쿼리와 병목 원인을 분석해 주세요.'
} | ConvertTo-Json

$taskReply = Invoke-RestMethod -Method POST -Uri "$apiBase/tasks/$teamId" `
    -Headers $agentHeaders -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($taskBody))
$taskId = $taskReply.data.taskId
$taskReply.data | ConvertTo-Json -Depth 10
```

브로커가 연결되지 않은 새 Agent는 오프라인입니다. 이 예시에서는 작업이 저장되어도 즉시 AI 실행이 시작되지 않으며, 응답의 `delivery`에서 전달 상태를 확인합니다. 실제 실행과 재전송을 확인하려면 Client의 브로커·MCP를 AI 도구에 연결하세요.

`requestKey`는 요청 Agent의 중복 작업 생성을 검증하는 데 사용합니다. 같은 작업 생성 요청을 재전송할 때는 같은 키와 본문을 사용합니다.

### 3. 웹 API로 목록·상세·대화 이력 조회

```powershell
$listReply = Invoke-DevLinkUserApi GET "/web/teams/$teamId/tasks"
$detailReply = Invoke-DevLinkUserApi GET "/web/teams/$teamId/tasks/$taskId"
$historyReply = Invoke-DevLinkUserApi GET "/web/teams/$teamId/tasks/$taskId/events"

$listReply.data | ConvertTo-Json -Depth 10
$detailReply.data | ConvertTo-Json -Depth 10
$historyReply.data | ConvertTo-Json -Depth 10
```

목록에는 소속 팀의 전체 작업이 표시됩니다. 상세 API는 작업 요약·상태·참여 Agent 등을, 이력 API는 요청 본문·결과·질문·답변 등의 기록을 제공합니다.

### 4. 담당 Agent 후보 추천

```powershell
$recommendBody = @{ question = 'API 응답 지연을 분석할 담당 Agent를 찾고 싶어요.' } |
    ConvertTo-Json
$recommendReply = Invoke-RestMethod -Method POST `
    -Uri "$apiBase/teams/$teamId/agents/recommendations" `
    -Headers $agentHeaders -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($recommendBody))
$recommendReply.data | ConvertTo-Json -Depth 10
```

추천은 역할 태그와 업로드된 Git 근거를 점수화하는 방식입니다. 후보의 연결 상태도 함께 제공하며, 추천만으로 새 작업을 생성하지 않습니다. 외부 LLM API 키 없이 사용할 수 있습니다.
