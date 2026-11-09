package com.example.classpulse.ui.online;

import com.example.classpulse.online.*;
import org.json.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class OnlineSessionFragment extends OnlineFragment {
    private boolean busy;
    private static final String[] TYPES = {"UNDERSTANDING", "SPEED", "TERM", "CALCULATION"};
    private static final String[] LABELS = {"이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요"};

    @Override
    protected String title() {
        return "수업 참여";
    }

    @Override
    protected void render() {
        if (shell == null || repo == null) return;
        clear();
        JSONObject s = lesson();
        if (s.optString("id").isEmpty()) {
            ui.note(shell.content, "수업 권한과 진도를 확인하고 있어요.");
            showNotice();
            return;
        }
        boolean teacher = owner();
        shell.roleLabel.setText(teacher ? "교수자" : "학생");
        JSONObject p = s.optJSONObject("progress");
        if (p == null) p = new JSONObject();
        final JSONObject progress = p;
        ui.heading(
                shell.content,
                s.optString("title"),
                "READY".equals(s.optString("status"))
                        ? "시작을 기다리고 있어요"
                        : "ENDED".equals(s.optString("status"))
                                ? "수업 종료 · 질문과 답변을 이어가요"
                                : "지금: " + topicTitle(p.optString("currentTopicId")));
        showNotice();
        boolean ended = "ENDED".equals(s.optString("status"));
        if (teacher || ended) {
            ui.button(shell.content, teacher ? "녹음·수업 자료" : "수업 원문·복습 자료",
                    false, () -> open("online_material"));
            ui.button(shell.content, teacher ? "복습 퀴즈 작성" : "복습 퀴즈 풀기",
                    false, () -> open("online_quizzes"));
        }
        if (teacher) {
            if ("READY".equals(s.optString("status"))) {
                ui.button(shell.content, "대주제 편집", false, () -> open("online_edit"));
                ui.button(shell.dock, "수업 시작", true, () -> progress("START", null))
                        .setEnabled(!busy);
            }
            if (!"ENDED".equals(s.optString("status")))
                ui.button(shell.content, "링크·코드로 초대", false, () -> open("online_invite"));
            if ("LIVE".equals(s.optString("status"))) {
                ui.note(shell.content, LectureRecording.isRecording(repo.uid, screen.lessonId)
                        ? "이 수업을 녹음하고 있어요."
                        : "녹음하려면 녹음·수업 자료에서 수업 녹음 시작을 눌러 주세요.");
                ui.button(
                                shell.dock,
                                busy ? "진도 확인 중…" : "완료하고 다음 주제",
                                true,
                                () -> progress("COMPLETE_NEXT", null))
                        .setEnabled(!busy && !progress.isNull("currentVisitId"));
                if (!p.isNull("resumeTopicId"))
                    ui.button(shell.content, "원래 진행 주제로 복귀", false, () -> progress("RESUME", null))
                            .setEnabled(!busy);
                ui.button(
                        shell.content,
                        "수업 종료",
                        false,
                        () ->
                                new MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("수업을 종료할까요?")
                                        .setMessage("종료 후에도 질문과 답변은 이어갈 수 있어요.")
                                        .setNegativeButton("계속 수업", null)
                                        .setPositiveButton("종료", (d, w) -> progress("END", null))
                                        .show());
            }
            JSONObject stats = item("STATS", "summary");
            ui.section(shell.content, "학생 반응", "반응은 익명으로 집계돼요.");
            ui.stats(
                    shell.content,
                    "참여 학생",
                    String.valueOf(stats.optInt("studentCount")),
                    "반응한 학생",
                    String.valueOf(stats.optInt("uniqueReactingStudents")));
            JSONObject counts = stats.optJSONObject("eventCountsByType");
            // 반응 유형별 건수를 막대로 비교한다. 가장 많은 유형이 막대 100%.
            int most = 0;
            for (int i = 0; i < 4; i++)
                most = Math.max(most, counts == null ? 0 : counts.optInt(TYPES[i]));
            for (int i = 0; i < 4; i++)
                ui.meter(
                        shell.content,
                        LABELS[i],
                        counts == null ? 0 : counts.optInt(TYPES[i]),
                        most);
            ui.button(
                    shell.content,
                    s.optBoolean("questionsOpen") ? "질문 접수 마감" : "질문 접수 다시 열기",
                    false,
                    () ->
                            repo.command(
                                    "setQuestionsOpen",
                                    screen.lessonId,
                                    OnlineJson.values(
                                            "sessionId",
                                            screen.lessonId,
                                            "baseRevision",
                                            s.optLong("revision"),
                                            "open",
                                            !s.optBoolean("questionsOpen")),
                                    callback(v -> render())));
        } else {
            ui.button(
                    shell.content,
                    "이 대주제에 질문하기",
                    true,
                    () ->
                            open(
                                    "online_question_edit",
                                    screen.withQuestion(OnlineJson.id())
                                            .withTopic(progress.optString("currentTopicId"))));
            ui.button(
                    shell.content,
                    "질문할 순간 표시",
                    false,
                    () -> {
                        String t = progress.optString("currentTopicId");
                        if (t.isEmpty()) {
                            error("수업 시작 후 현재 대주제를 표시할 수 있어요.");
                            return;
                        }
                        ui.prompt(
                                "개인 키워드",
                                "",
                                false,
                                v -> repo.saveMark(screen.lessonId, t, v, callback(x -> {})));
                    });
            if ("LIVE".equals(s.optString("status")) && !p.isNull("currentVisitId")) {
                ui.section(shell.content, "지금 수업은 어떤가요?", "반응은 교수에게 익명 집계로 전달돼요.");
                for (int row = 0; row < 2; row++) {
                    int a = row * 2, b = a + 1;
                    ui.pair(
                            shell.dock,
                            reactionLabel(a, p.optString("currentVisitId")),
                            () -> react(a, progress),
                            reactionLabel(b, p.optString("currentVisitId")),
                            () -> react(b, progress));
                }
            }
        }
        ui.section(shell.content, "수업 대주제", null);
        for (OnlineDatabase.Document d : topics()) {
            boolean complete = OnlineJson.contains(p.optJSONArray("completedTopicIds"), d.id),
                    selected = d.id.equals(p.optString("currentTopicId"));
            ui.topic(
                    shell.content,
                    OnlineJson.object(d.json).optString("title"),
                    selected ? "진행 중" : complete ? "완료" : "예정",
                    selected,
                    teacher && complete && "LIVE".equals(s.optString("status"))
                            ? () -> progress("REVISIT", d.id)
                            : null);
        }
        ui.section(shell.content, teacher ? "접수된 질문" : "내 질문", null);
        for (OnlineDatabase.Document d : kind("QUESTION")) {
            JSONObject q = OnlineJson.object(d.json);
            ui.card(
                    shell.content,
                    q.optString("text"),
                    topicTitle(q.optString("topicId")),
                    stateLabel(q.optString("status")),
                    () -> open("online_question", screen.withQuestion(d.id)));
        }
        moreButton("QUESTION", "이전 질문 더 불러오기");
        if (!teacher) {
            for (OnlineDatabase.Draft d : drafts)
                if ("QUESTION_DRAFT".equals(d.kind) && (!d.closed || "QUEUED".equals(d.state)))
                    ui.card(
                            shell.content,
                            d.text.isEmpty() ? "작성 중인 질문" : d.text,
                            topicTitle(d.topicId),
                            d.closed ? "전송 대기 · 접수 확인" : d.dirty ? "기기에 저장 · 전송 대기" : "개인 초안",
                            () ->
                                    open(
                                            "online_question_edit",
                                            screen.withQuestion(d.id).withTopic(d.topicId)));
            for (OnlineDatabase.Draft d : drafts)
                if ("MARK".equals(d.kind) && !"DELETED".equals(d.state))
                    ui.card(
                            shell.content,
                            d.text.isEmpty() ? "질문할 순간" : d.text,
                            topicTitle(d.topicId),
                            "나만 보는 표시",
                            () ->
                                    open(
                                            "online_question_edit",
                                            screen.withQuestion(OnlineJson.id())
                                                    .withTopic(d.topicId)));
        }
        ui.section(shell.content, "함께 보는 공개 Q&A", null);
        for (OnlineDatabase.Document d : kind("PUBLIC_QNA")) {
            JSONObject q = OnlineJson.object(d.json);
            ui.card(
                    shell.content,
                    q.optString("questionText"),
                    q.optString("answerText"),
                    topicTitle(q.optString("topicId")),
                    () ->
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(q.optString("questionText"))
                                    .setMessage(q.optString("answerText"))
                                    .setPositiveButton("확인", null)
                                    .show());
        }
        moreButton("PUBLIC_QNA", "이전 공개 Q&A 더 불러오기");
        if (!teacher) {
            for (OnlineDatabase.Conflict c : conflicts) {
                ui.note(
                        shell.content,
                        "다른 기기의 원고와 충돌했어요.\n이 기기: "
                                + c.localText
                                + "\n다른 기기: "
                                + OnlineJson.object(c.remoteJson).optString("text"));
                ui.pair(
                        shell.content,
                        "이 기기 원고 유지",
                        () -> repo.resolveConflict(c.sessionId, c.kind, c.id, true),
                        "다른 기기 원고 사용",
                        () -> repo.resolveConflict(c.sessionId, c.kind, c.id, false));
            }
        }
        pending();
        ui.button(shell.content, "전송 상태 다시 확인", false, repo::retry);
    }

    private String reactionLabel(int i, String visit) {
        for (OnlineDatabase.Document d : kind("OWN_REACTION")) {
            JSONObject r = OnlineJson.object(d.json);
            if (visit.equals(r.optString("visitId")) && TYPES[i].equals(r.optString("type")))
                return "✓ " + LABELS[i];
        }
        return LABELS[i];
    }

    private void react(int index, JSONObject p) {
        repo.background(
                "submitReaction",
                screen.lessonId,
                OnlineJson.values(
                        "sessionId",
                        screen.lessonId,
                        "topicId",
                        p.optString("currentTopicId"),
                        "visitId",
                        p.optString("currentVisitId"),
                        "type",
                        TYPES[index]),
                callback(v -> render()));
    }

    private void progress(String action, String topic) {
        if (busy) return;
        JSONObject s = lesson();
        JSONObject payload =
                OnlineJson.values(
                        "sessionId",
                        screen.lessonId,
                        "action",
                        action,
                        "expectedProgressVersion",
                        s.optLong("progressVersion"));
        if (topic != null)
            try {
                payload.put("topicId", topic);
            } catch (Exception ignored) {
            }
        busy = true;
        render();
        final long token = viewToken();
        final android.content.Context appContext = requireContext().getApplicationContext();
        final String recordingOwner = repo.uid;
        final String lessonId = screen.lessonId;
        repo.command(
                "changeProgress",
                screen.lessonId,
                payload,
                new OnlineRepository.Callback() {
                    public void success(JSONObject v) {
                        busy = false;
                        if ("END".equals(action) && LectureRecording.isRecording(recordingOwner, lessonId)) {
                            LectureRecording.stop(appContext);
                        }
                        if (current(token)) render();
                    }

                    public void error(String r, String m) {
                        busy = false;
                        if (current(token)) {
                            render();
                            OnlineSessionFragment.this.error(m);
                        }
                    }
                });
    }
}
