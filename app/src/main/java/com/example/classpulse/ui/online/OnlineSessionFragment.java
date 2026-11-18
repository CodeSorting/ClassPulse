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
        if (teacher || ended) {
            ui.button(shell.content, teacher ? "녹음·수업 자료" : "수업 원문·복습 자료",
                    false, () -> open("online_material"));
            ui.button(shell.content, teacher ? "복습 퀴즈 작성" : "복습 퀴즈 풀기",
                    false, () -> open("online_quizzes"));
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
                        ? "이 수업을 녹음하고 있어요."
                        : "녹음하려면 녹음·수업 자료에서 수업 녹음 시작을 눌러 주세요.");
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
                        ? "반응은 익명으로 전달돼요. 선택한 반응을 다시 누르면 취소돼요."
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
                    String visitId = p.optString("currentVisitId");
                    pair.findViewById(R.id.left).setEnabled(accepting && !reactionPending(a, visitId));
                    pair.findViewById(R.id.right).setEnabled(accepting && !reactionPending(b, visitId));
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

    private String reactionLabel(int index, String visitId) {
        OnlineDatabase.Document receipt = reactionReceipt(index, visitId);
        return receipt != null && !receipt.deleted ? "✓ " + LABELS[index] : LABELS[index];
    }

    private OnlineDatabase.Document reactionReceipt(int index, String visitId) {
        for (OnlineDatabase.Document document : docs) {
            if (!"OWN_REACTION".equals(document.kind)) continue;
            JSONObject receipt = OnlineJson.object(document.json);
            if (visitId.equals(receipt.optString("visitId")) && TYPES[index].equals(receipt.optString("type"))) {
                return document;
            }
        }
        return null;
    }

    private boolean reactionPending(int index, String visitId) {
        String key = OnlineRepository.reactionKey(screen.lessonId, visitId, TYPES[index]);
        for (OnlineDatabase.Outbox operation : operations) {
            if (key.equals(operation.entityKey) && !"ACKED".equals(operation.state)
                    && !"REJECTED".equals(operation.state)) return true;
        }
        return false;
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
        String visitId = p.optString("currentVisitId");
        if (reactionPending(index, visitId)) return;
        OnlineDatabase.Document receipt = reactionReceipt(index, visitId);
        boolean selected = receipt == null || receipt.deleted;
        repo.setReaction(screen.lessonId, p.optString("currentTopicId"), visitId, TYPES[index],
                selected, receipt == null ? 0 : receipt.revision, callback(v -> {
                    if (shell != null) shell.dock.announceForAccessibility(
                            selected ? "익명 반응을 보냈어요." : "반응을 취소했어요.");
                }));
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
