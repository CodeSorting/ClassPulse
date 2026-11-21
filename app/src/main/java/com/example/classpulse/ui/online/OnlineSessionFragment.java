package com.example.classpulse.ui.online;

import android.view.View;
import com.example.classpulse.R;
import com.example.classpulse.online.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class OnlineSessionFragment extends OnlineFragment {
    private boolean busy, intakeSettingsPending;
    private String recordingNotice = "";
    private static final String[] TYPES = {"UNDERSTANDING", "SPEED", "TERM", "CALCULATION"};
    private static final String[] LABELS = {"이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요"};

    @Override public void onStart() {
        super.onStart();
        LectureRecording.events().removeObservers(getViewLifecycleOwner());
        LectureRecording.events().observe(getViewLifecycleOwner(), event -> {
            if (repo != null && event != null && repo.uid.equals(event.uid) && screen.lessonId.equals(event.sessionId)) {
                recordingNotice = event.message;
                render();
            }
        });
    }

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
        String currentTopic = p.isNull("currentTopicId") ? "" : p.optString("currentTopicId");
        boolean childStep = !item("TOPIC", currentTopic).isNull("parentTopicId");
        List<OnlineDatabase.Document> outline = topics();
        Map<String, List<OnlineDatabase.Document>> children = new LinkedHashMap<>();
        for (OnlineDatabase.Document topic : outline) {
            JSONObject entry = OnlineJson.object(topic.json);
            if (!entry.isNull("parentTopicId"))
                children.computeIfAbsent(entry.optString("parentTopicId"), key -> new ArrayList<>()).add(topic);
        }
        ui.heading(
                shell.content,
                s.optString("title"),
                "READY".equals(s.optString("status"))
                        ? "시작을 기다리고 있어요"
                        : "ENDED".equals(s.optString("status"))
                                ? "수업 종료 · 질문과 답변을 이어가요"
                                : currentTopic.isEmpty() ? "모든 주제를 마쳤어요" : "지금: " + topicTitle(currentTopic));
        showNotice();
        renderOutline(outline, children, progress, teacher && "LIVE".equals(s.optString("status")));
        boolean ended = "ENDED".equals(s.optString("status"));
        if (ended) {
            reviewHub(teacher);
        } else if (teacher) {
            ui.button(shell.content, "녹음·수업 자료", false, () -> open("online_material"));
            ui.button(shell.content, "복습 퀴즈 작성", false, () -> open("online_quizzes"));
        }
        if (teacher) {
            if ("READY".equals(s.optString("status"))) {
                ui.button(shell.content, "목차 편집", false, () -> open("online_edit"));
                ui.button(shell.dock, "수업 시작", true, () -> progress("START", null))
                        .setEnabled(!busy);
            }
            if (!"ENDED".equals(s.optString("status")))
                ui.button(shell.content, "링크·코드로 초대", false, () -> open("online_invite"));
            if ("LIVE".equals(s.optString("status"))) {
                ui.note(shell.content, LectureRecording.isRecording(repo.uid, screen.lessonId)
                        ? "대단원 자동 녹음 사용 중 · 대단원 전환 시 업로드·STT, 수업 종료 시 마이크가 꺼져요."
                        : "녹음·수업 자료에서 자동 녹음을 한 번 켜면 다음 대단원도 이어서 녹음해요.");
                if (!recordingNotice.isEmpty()) ui.note(shell.content, recordingNotice);
                if (!p.isNull("currentVisitId") && !p.optString("currentVisitId").isEmpty()) {
                    String nextLabel = !p.isNull("resumeTopicId") && !p.optString("resumeTopicId").isEmpty()
                            ? "재설명 마치고 원래 주제로"
                            : hasNextTopic(p, outline, children) ? "완료하고 다음 주제"
                            : childStep ? "마지막 소주제 완료" : "마지막 대주제 완료";
                    ui.button(
                                    shell.dock,
                                    busy ? "진도 확인 중…" : nextLabel,
                                    true,
                                    () -> progress("COMPLETE_NEXT", null))
                            .setEnabled(!busy);
                } else {
                    ui.note(shell.content, "모든 주제를 마쳤어요. 수업을 종료하거나 완료한 주제를 다시 설명할 수 있어요.");
                }
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
                                        .setMessage("녹음은 즉시 멈추고 마지막 대단원은 자동 업로드·변환해요. 종료 후에도 질문과 답변은 이어갈 수 있어요.")
                                        .setNegativeButton("계속 수업", null)
                                        .setPositiveButton("종료", (d, w) -> progress("END", null))
                                        .show()).setEnabled(!busy);
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
                            intakeSettingsPending ? "접수 설정 중…"
                                    : s.optBoolean("questionsOpen", true) ? "질문·반응 접수 마감" : "질문·반응 접수 다시 열기",
                            false,
                            () -> setQuestionsOpen(!s.optBoolean("questionsOpen", true)))
                    .setEnabled(!intakeSettingsPending);
            ui.note(shell.content, "접수를 마감하면 학생의 질문 제출과 네 가지 반응을 함께 막아요. 작성 중인 질문은 초안으로 보관돼요.");
        } else {
            ui.button(
                    shell.content,
                    childStep ? "이 소주제에 질문하기" : currentTopic.isEmpty() ? "질문하기" : "이 대주제에 질문하기",
                    true,
                    () ->
                            open(
                                    "online_question_edit",
                                    screen.withQuestion(OnlineJson.id())
                                            .withTopic(currentTopic)));
            ui.button(
                    shell.content,
                    "질문할 순간 표시",
                    false,
                    () -> {
                        if (currentTopic.isEmpty()) {
                            error("수업이 진행 중일 때 현재 주제를 표시할 수 있어요.");
                            return;
                        }
                        ui.prompt(
                                "개인 키워드",
                                "",
                                false,
                                v -> repo.saveMark(screen.lessonId, currentTopic, v, callback(x -> {})));
                    });
            if ("LIVE".equals(s.optString("status")) && !p.isNull("currentVisitId")
                    && !p.optString("currentVisitId").isEmpty()) {
                boolean accepting = s.optBoolean("questionsOpen", true);
                ui.section(shell.content, "지금 수업은 어떤가요?", accepting
                        ? "반응은 교수에게 익명 집계로 전달돼요."
                        : "교수님이 질문·반응 접수를 마감했어요. 다시 열리면 참여할 수 있어요.");
                for (int row = 0; row < 2; row++) {
                    int a = row * 2, b = a + 1;
                    ui.pair(
                            shell.dock,
                            reactionLabel(a, p.optString("currentVisitId")),
                            () -> react(a, progress),
                            reactionLabel(b, p.optString("currentVisitId")),
                            () -> react(b, progress));
                    View pair = shell.dock.getChildAt(shell.dock.getChildCount() - 1);
                    pair.findViewById(R.id.left).setEnabled(accepting);
                    pair.findViewById(R.id.right).setEnabled(accepting);
                }
            }
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

    /** 종료된 수업의 복습 메뉴. 전체 원문과 요약을 따로 열 수 있게 나눈다. */
    private void reviewHub(boolean teacher) {
        ui.section(shell.content, "수업 후 복습",
                teacher ? "녹음을 원문으로 바꾸고, 요약과 퀴즈를 검토해 학생에게 공개해요."
                        : "전체 원문과 요약을 나눠서 보고, 퀴즈로 확인해요.");
        if (teacher)
            ui.card(shell.content, "녹음·전체 원문 검토", "녹음 업로드, STT 변환, 오인식 수정 후 공개해요.",
                    "검토·공개", () -> open("online_material"));
        else
            ui.card(shell.content, "전체 강의 원문", "수업 처음부터 끝까지 구간별로 읽어요.",
                    "원문", () -> open("online_transcript"));
        ui.card(shell.content, "대주제별 요약·보충 설명",
                teacher ? "AI 초안을 고쳐서 대주제마다 공개해요." : "핵심만 빠르게 다시 봐요.",
                "요약", () -> open("online_summary"));
        ui.card(shell.content, teacher ? "복습 퀴즈 출제" : "복습 퀴즈 풀기",
                teacher ? "직접 쓰거나 AI 초안으로 문제를 만들어요." : "내 속도로 풀고 해설을 확인해요.",
                "퀴즈", () -> open("online_quizzes"));
        if (!teacher)
            ui.card(shell.content, "내 질문·표시 기록", "궁금했던 순간과 작성 중인 질문을 다시 찾아요.",
                    "나만 보는 기록", () -> open("online_records", screen.forLesson(screen.lessonId)));
        ui.note(shell.content, "공개 Q&A는 이 화면 아래쪽에서 대주제별로 확인할 수 있어요.");
    }

    private String reactionLabel(int i, String visit) {
        for (OnlineDatabase.Document d : kind("OWN_REACTION")) {
            JSONObject r = OnlineJson.object(d.json);
            if (visit.equals(r.optString("visitId")) && TYPES[i].equals(r.optString("type")))
                return "✓ " + LABELS[i];
        }
        return LABELS[i];
    }

    private void renderOutline(List<OnlineDatabase.Document> outline,
                               Map<String, List<OnlineDatabase.Document>> children,
                               JSONObject progress, boolean canRevisit) {
        if (outline.isEmpty()) return;
        ui.section(shell.content, "수업 목차", null);
        String current = progress.optString("currentTopicId");
        JSONArray completed = progress.optJSONArray("completedTopicIds");
        int groupNumber = 0;
        for (OnlineDatabase.Document group : outline) {
            JSONObject entry = OnlineJson.object(group.json);
            if (!entry.isNull("parentTopicId")) continue;
            groupNumber++;
            List<OnlineDatabase.Document> groupChildren = children.get(group.id);
            if (groupChildren == null) {
                renderTopic(group, groupNumber + ".", false, current, completed, canRevisit);
                continue;
            }
            int done = 0;
            boolean active = false;
            for (OnlineDatabase.Document child : groupChildren) {
                if (OnlineJson.contains(completed, child.id)) done++;
                if (current.equals(child.id)) active = true;
            }
            String groupStatus = done == groupChildren.size() ? "완료" : done + "/" + groupChildren.size() + " 완료";
            ui.topic(shell.content, groupNumber + ". " + entry.optString("title"),
                    active ? "진행 중 · " + groupStatus : groupStatus, active, null);
            for (int i = 0; i < groupChildren.size(); i++)
                renderTopic(groupChildren.get(i), groupNumber + "." + (i + 1),
                        true, current, completed, canRevisit);
        }
    }

    private void renderTopic(OnlineDatabase.Document topic, String number, boolean child,
                             String current, JSONArray completed, boolean canRevisit) {
        boolean done = OnlineJson.contains(completed, topic.id);
        boolean active = current.equals(topic.id);
        ui.topic(shell.content, number + " " + OnlineJson.object(topic.json).optString("title"),
                active ? "진행 중" : done ? "완료" : "예정", active, child,
                canRevisit && done && !active ? () -> progress("REVISIT", topic.id) : null);
    }

    private boolean hasNextTopic(JSONObject progress, List<OnlineDatabase.Document> outline,
                                 Map<String, List<OnlineDatabase.Document>> children) {
        String current = progress.optString("currentTopicId");
        for (OnlineDatabase.Document topic : outline)
            if (!children.containsKey(topic.id) && !topic.id.equals(current)
                    && !OnlineJson.contains(progress.optJSONArray("completedTopicIds"), topic.id)) return true;
        return false;
    }

    private void setQuestionsOpen(boolean open) {
        if (intakeSettingsPending) return;
        JSONObject s = lesson();
        intakeSettingsPending = true;
        render();
        final long token = viewToken();
        repo.command(
                "setQuestionsOpen", screen.lessonId,
                OnlineJson.values("sessionId", screen.lessonId, "baseRevision", s.optLong("revision"), "open", open),
                new OnlineRepository.Callback() {
                    public void success(JSONObject value) {
                        intakeSettingsPending = false;
                        if (current(token)) render();
                    }

                    public void error(String reason, String message) {
                        intakeSettingsPending = false;
                        if (current(token)) {
                            render();
                            OnlineSessionFragment.this.error(message);
                        }
                    }
                });
    }

    private void react(int index, JSONObject p) {
        JSONObject current = lesson();
        JSONObject currentProgress = current.optJSONObject("progress");
        if (!current.optBoolean("questionsOpen", true)) {
            error("교수님이 질문·반응 접수를 마감했어요.");
            return;
        }
        if (!"LIVE".equals(current.optString("status")) || currentProgress == null
                || p.isNull("currentVisitId") || currentProgress.isNull("currentVisitId")
                || !p.optString("currentVisitId").equals(currentProgress.optString("currentVisitId"))) {
            error("진도가 바뀌었어요. 현재 대주제를 다시 확인해 주세요.");
            return;
        }
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
        if ("END".equals(action) && LectureRecording.isRecording(repo.uid, screen.lessonId)) {
            LectureRecording.stop(requireContext());
        }
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
        final String recordingOwner = repo.uid;
        final String lessonId = screen.lessonId;
        repo.command(
                "changeProgress",
                screen.lessonId,
                payload,
                new OnlineRepository.Callback() {
                    public void success(JSONObject v) {
                        busy = false;
                        LectureRecording.progress(recordingOwner, lessonId, v.optJSONObject("session"));
                        if (current(token)) render();
                    }

                    public void error(String r, String m) {
                        busy = false;
                        if (current(token)) {
                            render();
                            OnlineSessionFragment.this.error("END".equals(action)
                                    ? m + " 녹음은 이미 멈췄어요. 수업 종료를 다시 시도해 주세요." : m);
                        }
                    }
                });
    }
}
