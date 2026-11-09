# ClassPulse

## 10주차 CLOVA Speech·GPT 연동 확인

프로젝트 루트에서 Node.js 22 이상으로 실행합니다. 서버의 API 호출 형식과 오류 처리를 확인하는 테스트는 API 키 없이 실행할 수 있습니다.

```powershell
node --test functions/test/ai-providers.test.js
```

### 발급받은 키로 확인

프로젝트 루트에 Git에서 제외되는 `.env.integrations.local`을 만들고 발급받은 값을 채웁니다. CLOVA Speech Invoke URL은 장문 인식 도메인의 연동 정보에 있는 기본 URL을 넣습니다.

```dotenv
CLOVA_SPEECH_INVOKE_URL=
CLOVA_SPEECH_SECRET_KEY=
OPENAI_API_KEY=
OPENAI_MODEL=gpt-5-mini
```

아래 명령은 각각 실제 API를 한 번 호출합니다. STT는 본인이 녹음한 5~10초 한국어 샘플을 사용합니다. WAV, MP3, M4A를 사용할 수 있고 파일 크기는 5 MiB까지입니다. GPT는 스크립트의 예시 질문을 사용합니다.

```powershell
node --env-file=.env.integrations.local scripts/check-ai-providers.js stt 'C:\path\to\sample.m4a'
node --env-file=.env.integrations.local scripts/check-ai-providers.js gpt
```

성공 시 변환 글자 수와 구간 수 또는 질문 초안의 글자 수가 표시됩니다. `CONFIGURATION`이면 환경 변수 값과 Invoke URL을, `AUTHENTICATION`이면 발급 키와 도메인·프로젝트 권한을, `RATE_LIMITED`이면 사용 한도를 확인합니다.

## 온라인 수업에서 녹음·AI·퀴즈 사용하기

1. 교수 계정으로 수업을 시작하고 **녹음·수업 자료 → 수업 녹음 시작**을 누릅니다. 녹음을 시작하기 전에 참여자에게 녹음 사실을 알립니다. 수업을 종료하거나 **녹음 종료 및 저장**을 누르면 앱에 녹음을 보관합니다.
2. 종료된 수업에서 **녹음 업로드 → STT 변환 요청**을 누릅니다. 원문이 준비되면 시간 구간과 대주제를 확인하고 필요한 부분을 수정한 뒤 **전체 원문 공개**를 누릅니다.
3. **대주제 자료 편집**에서 주제를 선택해 요약과 보충 설명을 작성하거나 AI 초안을 요청합니다. 확인하고 수정한 내용을 공개하면 참여 학생이 종료 수업에서 볼 수 있습니다.
4. **복습 퀴즈 작성**에서 대주제를 고르고 직접 문제를 작성하거나 AI로 초안을 만듭니다. 제목, 보기 4개, 정답, 해설을 검토하고 수업 종료 후 공개합니다.
5. 참여 학생은 **복습 퀴즈 풀기**에서 문제를 풀고 제출합니다. 서버가 학생이 연 버전으로 채점합니다. 정답과 해설은 제출 후 보여 주고, 다시 풀면 별도 결과로 기록합니다.

학생 질문 화면에서 음성 입력과 AI 질문 다듬기를 사용할 수 있고, 교수는 제출된 질문에서 AI 답변 초안을 받을 수 있습니다. 생성된 문구는 확인 후 원고에 반영됩니다.

기존 녹음으로 STT를 확인하려면 교수 계정으로 테스트 수업을 종료한 뒤 **녹음·수업 자료 → 테스트 녹음 파일 선택 (.m4a)**에서 64 MiB 이하의 파일을 선택합니다. **녹음 업로드 → STT 변환 요청**을 누르면 됩니다. 가져온 파일은 해당 수업의 시작 시점을 알 수 없으므로 원문을 대주제 미지정으로 표시합니다. 필요하면 **이 구간 수정**에서 대주제를 선택합니다.

### Firebase 배포

- 대상은 팀 개발 프로젝트 `classpulse-dev-edca7`입니다. Android 설정 파일에 이 프로젝트의 기본 Storage 버킷이 포함돼 있어야 합니다.
- `classPulseLearning`이 사용하는 `CLOVA_SPEECH_INVOKE_URL`, `CLOVA_SPEECH_SECRET_KEY`, `OPENAI_API_KEY`를 Firebase Secret Manager에 등록합니다. 로컬 키는 프로젝트 루트의 `.env.integrations.local`에서 사용합니다.
- Storage 규칙은 녹음 경로의 수업 소유권을 Firestore에서 확인합니다. 최초 배포 시 Firebase Storage의 Firestore 규칙 조회 연동 권한이 필요합니다. 기존 Storage 규칙이 있다면 녹음 경로를 추가해 합친 뒤 배포합니다.
- `classPulseSttResult`는 CLOVA가 결과를 보내는 서버 주소입니다. 작업별 확인값과 현재 작업 상태를 검증한 다음 교수의 원문 초안을 생성합니다.

```powershell
npm.cmd ci
npm.cmd ci --prefix functions
npm.cmd run test:server
npx.cmd firebase deploy --project classpulse-dev-edca7 --only 'functions:classpulse:classPulseLearning,functions:classpulse:classPulseSttResult,storage'
```
