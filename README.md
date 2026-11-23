# ClassPulse

Java + XML Views로 구현한 Android 수업 소통 앱입니다. Room을 사용하는 기기 내 시연과 Firebase 계정으로 접속하는 온라인 수업을 구분합니다.

## 기본 화면과 수업 기능

- Google 로그인 후 **수업 · 내 기록 · 내 정보**의 하단 세 탭으로 이동합니다. 로그아웃은 내 정보 화면에 있습니다.
- 교수는 수업과 대주제를 만들고 4자리 코드 또는 초대 링크를 공유합니다. 학생은 수업 정보를 확인한 뒤 명시적으로 참여합니다.
- 교수의 진도에 따라 학생은 이해됐어요를 포함한 다섯 가지 반응과 주제별 질문을 남깁니다. 반응은 다시 눌러 취소하고, 미답변 질문은 제출 후 취소할 수 있습니다. 교수는 대주제별 반응 집계와 제출 질문을 확인하고 답변을 검토한 뒤 공개합니다.
- 학생에게 강의 STT 전문을 실시간으로 보내지 않습니다. 교수의 녹음은 대단원별로 종료 즉시 STT로 변환하고, 교수가 검토·공개한 전체 원문과 요약을 학생에게 제공합니다.
- 김진호의 녹음·STT·AI·복습 퀴즈 온라인 연동 코드가 포함돼 있습니다. 사용하려면 아래의 Firebase Storage·Functions·서버 키 설정이 필요합니다.
- 기존 Room 기반 질문·개인 기록 캐시는 유지합니다. 새 온라인 원문·요약·퀴즈의 Room 다운로드와 오프라인 복습 연결은 후속 작업입니다.
- 학생 수업 화면은 공통 Java·XML 화면을 사용합니다. 대주제·소주제 목차, 다섯 반응, 하단 대주제 선택과 질문 입력을 함께 표시하며, 작성 내용과 질문 주제는 진도 변경·화면 재생성 때도 유지합니다.
- 교수의 종료 수업 화면은 원문 검토·공개, 요약·보충 설명, 복습 퀴즈의 세 단계로 나눕니다. 학생은 공개된 대주제별 요약과 보충 설명을 화면 본문에서 바로 읽습니다.

시연 수업 코드는 **4821**입니다. 온라인 수업 코드는 교수가 해당 수업에서 발급합니다. 로그인한 온라인 수업과 같은 기기의 로컬 시연은 별도 경로입니다.

## Android 빌드

Firebase Console에서 이 Android 앱에 맞는 `google-services.json`을 받아 `app/`에 둡니다. Google 로그인에는 빌드에 사용하는 서명 인증서의 SHA-1 등록이 필요합니다. 팀원의 개발 서명이 다르면 해당 SHA-1도 등록합니다. Firebase 앱 구성 파일은 별도로 전달하며, 서버용 CLOVA·OpenAI 비밀키는 앱이나 Git에 넣지 않습니다.

```powershell
.\gradlew.bat :app:assembleDebug
```

APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 단위 테스트는 `.\gradlew.bat :app:testDebugUnitTest`로 실행합니다.

## 검증 범위와 남은 확인

2026-11-23 `12주차_김진호`·`12주차_이재민` 병합본에서 APK와 테스트 APK 빌드, JVM 단위 테스트 20개, Firebase Emulator 서버·권한 테스트 34개를 통과했습니다. Android 주요 항목 33개(온라인 수업·기록·자료 24개, 자동 녹음 4개, 학생 공통 화면 3개, 로컬 수업·교수 집계 2개)도 항목별로 재검증했습니다. 대단원 자동 업로드·STT 요청, 소단원 이동 시 녹음 유지, 백그라운드에서 원격 수업 종료, 통신 끊김 시 마이크 중단, 저장 정보 복구뿐 아니라 반응 취소·질문 취소·빈 초안 방지와 수업 후 단계별 화면이 함께 유지되는지 확인했습니다.

이 검증은 실제 휴대폰 두 대의 통합 테스트나 유료 CLOVA·GPT API 전체 동작 확인을 대신하지 않습니다. 서버 테스트는 `demo-classpulse` 에뮬레이터와 외부 API 모형을 사용합니다. Android 테스트는 실제 마이크 서비스와 로컬 Firebase 업로드·STT 요청까지 확인하며 유료 API는 호출하지 않습니다. 공기계에서는 아래 대단원 자동 녹음 절차로 실제 원문 결과와 수업 후 요약·퀴즈 동선을 확인합니다.

STT 요청의 일일 사용량 제한과 녹음 원본의 보관·삭제 정책도 공개 운영 전에 정해야 합니다.

## 개발 PC의 로컬 안내서

기존 제작 과정과 설계 자료는 개발 PC의 `AGENTS.md`와 `docs/`에 보관돼 있습니다. 이 파일들은 이번 병합의 GitHub 업로드 대상에 포함하지 않으므로, 새로 clone한 팀원에게는 별도 전달이 필요합니다. 문서의 과거 구현 상태는 작성 시점의 기록으로 읽습니다.

- 입문 안내: `docs/ClassPulse_제작과정_전체안내서.md`, `docs/guide/README.md`
- 제품 요구와 인계: `docs/PRODUCT_SPEC.md`, `docs/HANDOFF.md`
- 구조와 데이터 설계: `docs/ARCHITECTURE.md`, `docs/FIREBASE_DATA_DESIGN.md`
- Firebase 연결 및 검증: `docs/FIREBASE_IMPLEMENTATION.md`, `docs/FIREBASE_LIVE_TEST.md`, `docs/QA_CHECKLIST.md`, `docs/verification/STATUS.md`
- v6 UI 참고 원본: `docs/design/v6/ClassPulse_v6_Full_UI.svg`

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

1. 교수 계정으로 수업을 시작하고 **녹음·수업 자료 → 대단원 자동 녹음 시작**을 한 번 누릅니다. 녹음 전에 참여자에게 녹음 사실을 알립니다. 같은 대단원의 소단원을 이동할 때는 녹음을 유지하고, 다른 대단원으로 이동하면 이전 파일을 저장·업로드·STT 요청한 뒤 다음 대단원을 녹음합니다. 업로드와 STT를 기다릴 필요가 없습니다.
2. **녹음별 처리 상태**에서 각 파일의 업로드 대기, 변환 중, 변환 완료, 실패 상태를 확인합니다. 원문은 녹음 순서대로 누적되고 해당 대단원에 연결됩니다. 변환이 끝난 대단원부터 검토와 AI 요약·퀴즈 초안 생성을 진행할 수 있습니다. 검토 후 **전체 원문 공개**를 누릅니다.
3. **대주제 자료 편집**에서 주제를 선택해 요약과 보충 설명을 작성하거나 AI 초안을 요청합니다. 확인하고 수정한 내용을 공개하면 참여 학생이 종료 수업에서 볼 수 있습니다.
4. **복습 퀴즈 작성**에서 대주제를 고르고 직접 문제를 작성하거나 AI로 초안을 만듭니다. 제목, 보기 4개, 정답, 해설을 검토하고 수업 종료 후 공개합니다.
5. 참여 학생은 **복습 퀴즈 풀기**에서 문제를 풀고 제출합니다. 서버가 학생이 연 버전으로 채점합니다. 정답과 해설은 제출 후 보여 주고, 다시 풀면 별도 결과로 기록합니다.

학생 질문 화면에서 음성 입력과 AI 질문 다듬기를 사용할 수 있고, 교수는 제출된 질문에서 AI 답변 초안을 받을 수 있습니다. 생성된 문구는 확인 후 원고에 반영됩니다.

기존 녹음으로 STT를 확인하려면 교수 계정으로 테스트 수업을 종료한 뒤 **녹음·수업 자료 → 테스트 녹음 파일 선택 (.m4a)**에서 64 MiB 이하의 파일을 선택합니다. **녹음 업로드 → STT 변환 요청**을 누르면 됩니다. 가져온 파일은 해당 수업의 시작 시점을 알 수 없으므로 원문을 대주제 미지정으로 표시합니다. 필요하면 **이 구간 수정**에서 대주제를 선택합니다.

### 대단원 자동 녹음 확인

- 대단원 1에 소단원 두 개, 대단원 2에 소단원 한 개를 넣은 테스트 수업을 만듭니다. 각 구간에서 10초 이상 말하며 시험합니다.
- 소단원 1에서 2로 이동해도 녹음 파일이 나뉘지 않는지 확인합니다. 대단원 2로 이동하면 대단원 1 파일이 처리 목록에 나타나고 녹음 알림이 계속 유지돼야 합니다.
- **수업 종료 → 종료**를 누르면 서버 응답을 기다리기 전에 마이크를 해제합니다. 마지막 파일의 업로드·변환만 계속하며, 이미 끝난 대단원 원문이 남아 있어야 합니다. 수업 종료 요청에 실패한 경우 녹음은 중단된 상태이고 수업 종료를 다시 요청해야 합니다.
- 다른 기기에서 수업을 종료해도 녹음 기기가 종료 상태를 수신하면 자동으로 멈춥니다. 마지막 주제를 완료했으나 수업은 아직 종료하지 않았다면 마이크를 끄고 재설명 시작을 기다립니다.
- 수업 연결 끊김이나 계정 변경을 감지하면 녹음을 중단합니다. 연결이 돌아와도 마이크를 자동으로 다시 켜지 않습니다. 필요하면 **대단원 자동 녹음 시작**을 다시 누릅니다. 저장된 파일은 기기에 보관되며 업로드 대기는 재연결 후 진행합니다. 실패한 파일은 **이 녹음 다시 처리**로 재시도합니다.
- 파일마다 녹음을 닫고 다시 여는 방식이라 대단원 전환 순간에 짧은 간격이 생길 수 있습니다. 강제 종료·전원 차단으로 아직 닫지 못한 M4A의 복구는 보장되지 않으며, 앞서 완료한 대단원 파일은 별도로 보관됩니다.

서버에는 녹음별 처리 작업을 저장하므로 **Android 앱과 `classPulseLearning`, `classPulseSttResult`를 함께 업데이트**해야 합니다. 자동 녹음은 온라인 수업에서 사용합니다. 실제 CLOVA 처리 시간과 사용량은 계정의 플랜·제공자 상태에 따릅니다.

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
