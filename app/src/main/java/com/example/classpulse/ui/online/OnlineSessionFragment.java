package com.example.classpulse.ui.online;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.classpulse.domain.Models;
import com.example.classpulse.online.*;
import com.example.classpulse.ui.Ui;
import com.example.classpulse.ui.StudentLessonView;
import com.example.classpulse.ui.LessonOutlineView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class OnlineSessionFragment extends OnlineFragment {
    private boolean busy, intakeSettingsPending;
    private String reactionTopicId = "", lastReactionProgress = "";
    private StudentLessonView studentView;
    private Bundle studentState;
    private boolean studentLayout;
    private String recordingNotice = "";

    @Override
    public void onStart() {
        super.onStart();
        LectureRecording.events().removeObservers(getViewLifecycleOwner());
        LectureRecording.events().observe(getViewLifecycleOwner(), event -> {
            if (repo != null && event != null && repo.uid.equals(event.uid)
                    && screen.lessonId.equals(event.sessionId)) {
                recordingNotice = event.message;
                render();
            }
        });
    }

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (state != null) {
            studentState = state.getBundle("studentLesson");
            reactionTopicId = state.getString("reactionTopicId", "");
            lastReactionProgress = state.getString("lastReactionProgress", "");
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("reactionTopicId", reactionTopicId);
        state.putString("lastReactionProgress", lastReactionProgress);
        state.putBundle("studentLesson", studentView == null ? studentState : studentView.saveState());
    }

    @Override
    public void onStop() {
        if (studentView != null) studentView.stop();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (studentView != null) studentView.release();
        studentLayout = false;
        super.onDestroyView();
    }

    @Override
    protected String title() {
        return "수업 참여";
    }

    @Override
    protected void render() {
        if (shell == null || repo == null) return;
        JSONObject s = lesson();
        if (documentsLoaded && operationsLoaded && draftsLoaded && !owner()
                && ("LIVE".equals(s.optString("status")) || "READY".equals(s.optString("status")))) {
            renderStudentLesson(s);
            return;
        }
        if (studentLayout) {
            studentView.release();
            studentLayout = false;
        }
        shell.demoBanner.setVisibility(View.VISIBLE);
        shell.content.setPadding(dp(24), dp(22), dp(24), dp(24));
        shell.dock.setPadding(dp(24), dp(12), dp(24), dp(12));
        clear();
        if (s.optString("id").isEmpty()) {
            ui.note(shell.content, "수업 권한과 진도를 확인하고 있어요.");
            showNotice();
            return;
        }
        if (!documentsLoaded || !operationsLoaded || (!owner() && !draftsLoaded)) {
            ui.note(shell.content, "수업 내용과 전송 상태를 불러오고 있어요.");
            showNotice();
            return;
        }
        boolean teacher = owner();
        shell.roleLabel.setText(teacher ? "교수자" : "학생");
        JSONObject p = s.optJSONObject("progress");
        if (p == null) p = new JSONObject();
        final JSONObject progress = p;
        boolean ended = "ENDED".equals(s.optString("status"));
        String currentTopic = ended || p.isNull("currentTopicId") ? "" : p.optString("currentTopicId");
        boolean childStep = !item("TOPIC", currentTopic).isNull("parentTopicId");
        List<OnlineDatabase.Document> outline = topics();
        Map<String, List<OnlineDatabase.Document>> children = new LinkedHashMap<>();
        for (OnlineDatabase.Document topic : outline) {
            JSONObject entry = OnlineJson.object(topic.json);
            if (!entry.isNull("parentTopicId"))
                children.computeIfAbsent(entry.optString("parentTopicId"), key -> new ArrayList<>()).add(topic);
        }
        ui.lessonHeader(shell.content, s.optString("title"), s.optString("status"));
        if (!ended) {
            shell.demoBanner.setVisibility(View.GONE);
            shell.content.setPadding(dp(12), dp(10), dp(12), dp(16));
            shell.dock.setPadding(dp(12), dp(8), dp(12), dp(10));
        }
        if (ended) ui.note(shell.content, "수업 종료 · 질문과 답변을 이어가요");
        else if ("LIVE".equals(s.optString("status")) && currentTopic.isEmpty())
            ui.note(shell.content, "모든 주제를 마쳤어요");
        showNotice();
        if (!ended)
            renderOutline(outline, progress, teacher && "LIVE".equals(s.optString("status")));
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
            ui.section(shell.content, "대주제별 학생 반응", "좌우로 넘겨 확인해요. 재설명 반응도 해당 대주제에 누적돼요.");
            ui.stats(
                    shell.content,
                    "전체 참여 학생",
                    String.valueOf(stats.optInt("studentCount")),
                    "전체 반응 학생",
                    String.valueOf(stats.optInt("uniqueReactingStudents")));
            renderReactionPages(outline, currentTopic);
            ui.button(
                            shell.content,
                            intakeSettingsPending ? "접수 설정 중…"
                                    : s.optBoolean("questionsOpen", true) ? "질문·반응 접수 마감" : "질문·반응 접수 다시 열기",
                            false,
                            () -> setQuestionsOpen(!s.optBoolean("questionsOpen", true)))
                    .setEnabled(!intakeSettingsPending);
            ui.note(shell.content, "접수를 마감하면 학생의 질문 제출과 반응을 함께 막아요. 작성 중인 질문은 초안으로 보관돼요.");
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
            // QA 9: '질문할 순간 표시' 버튼은 질문 작성 화면의 '개인 초안 저장'과 역할이 겹쳐 제거했다.
            // 예전에 저장된 개인 표시는 아래 목록과 내 기록에서 계속 보인다.
            ui.note(shell.content, "질문을 다 쓰지 못했다면 질문 화면에서 '개인 초안 저장'을 눌러 두고 나중에 이어서 써요.");
        }
        renderSessionRecords(shell.content, teacher);
    }

    private void renderSessionRecords(LinearLayout target, boolean teacher) {
        ui.section(target, teacher ? "접수된 질문" : "내 질문", null);
        for (OnlineDatabase.Document d : kind("QUESTION")) {
            JSONObject q = OnlineJson.object(d.json);
            ui.card(
                    target,
                    q.optString("text"),
                    topicTitle(q.optString("topicId")),
                    stateLabel(q.optString("status")),
                    () -> open("online_question", screen.withQuestion(d.id)));
        }
        moreButton(target, "QUESTION", "이전 질문 더 불러오기");
        if (!teacher) {
            for (OnlineDatabase.Draft d : drafts)
                if (visibleQuestionDraft(d)
                        && (studentView == null || !studentLayout || !studentView.editingDraft(d.id) || d.closed))
                    questionDraftCard(target, d, d.closed ? "전송 대기 · 접수 확인"
                            : d.dirty ? "기기에 저장 · 전송 대기" : "개인 초안");
            for (OnlineDatabase.Draft d : drafts)
                if ("MARK".equals(d.kind) && !"DELETED".equals(d.state))
                    ui.card(
                            target,
                            d.text.isEmpty() ? "질문할 순간" : d.text,
                            topicTitle(d.topicId),
                            "나만 보는 표시",
                            () ->
                                    open(
                                            "online_question_edit",
                                            screen.withQuestion(OnlineJson.id())
                                                    .withTopic(d.topicId)));
        }
        ui.section(target, "함께 보는 공개 Q&A", null);
        for (OnlineDatabase.Document d : kind("PUBLIC_QNA")) {
            JSONObject q = OnlineJson.object(d.json);
            ui.card(
                    target,
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
        moreButton(target, "PUBLIC_QNA", "이전 공개 Q&A 더 불러오기");
        if (!teacher) {
            for (OnlineDatabase.Conflict c : conflicts) {
                ui.note(
                        target,
                        "다른 기기의 원고와 충돌했어요.\n이 기기: "
                                + c.localText
                                + "\n다른 기기: "
                                + OnlineJson.object(c.remoteJson).optString("text"));
                ui.pair(
                        target,
                        "이 기기 원고 유지",
                        () -> repo.resolveConflict(c.sessionId, c.kind, c.id, true),
                        "다른 기기 원고 사용",
                        () -> repo.resolveConflict(c.sessionId, c.kind, c.id, false));
            }
        }
        pending(target);
        ui.button(target, "전송 상태 다시 확인", false, repo::retry);
    }

    private void renderStudentLesson(JSONObject session) {
        if (studentView == null) {
            final OnlineRepository questionRepository = repo;
            final String questionSession = screen.lessonId;
            studentView = new StudentLessonView(requireContext(), studentState, new StudentLessonView.Actions() {
                public void react(String type) {
                    JSONObject progress = lesson().optJSONObject("progress");
                    if (progress != null) OnlineSessionFragment.this.react(type, progress);
                }

                public void save(String id, String topic, String text, boolean submit, StudentLessonView.Result result) {
                    questionRepository.saveDraft(questionSession, id, topic, text, submit, new OnlineRepository.Callback() {
                        public void success(JSONObject value) { result.success(); }
                        public void error(String reason, String message) { result.error(message); }
                    });
                }

                public void edit(String id, String topic) {
                    if (shell != null) open("online_question_edit", screen.withQuestion(id).withTopic(topic));
                }

                public void error(String message) { OnlineSessionFragment.this.error(message); }
            });
        }
        if (!studentLayout) {
            clear();
            shell.demoBanner.setVisibility(View.GONE);
            shell.content.setPadding(dp(12), dp(10), dp(12), dp(16));
            shell.dock.setPadding(dp(12), dp(8), dp(12), dp(10));
            studentView.attach(shell.content, shell.dock);
            studentLayout = true;
        }
        shell.roleLabel.setText("학생");
        JSONObject progress = session.optJSONObject("progress");
        if (progress == null) progress = new JSONObject();
        String visit = progress.optString("currentVisitId", "");
        String current = progress.optString("currentTopicId", "");
        boolean live = "LIVE".equals(session.optString("status")) && !progress.isNull("currentVisitId") && !visit.isEmpty();
        List<LessonOutlineView.Topic> outline = new ArrayList<>();
        for (OnlineDatabase.Document document : topics()) {
            JSONObject entry = OnlineJson.object(document.json);
            outline.add(new LessonOutlineView.Topic(document.id,
                    entry.isNull("parentTopicId") ? null : entry.optString("parentTopicId"),
                    entry.optString("title"), OnlineJson.contains(progress.optJSONArray("completedTopicIds"), document.id),
                    live && document.id.equals(current)));
        }
        Set<String> selected = new HashSet<>(), waiting = new HashSet<>();
        for (String type : Models.REACTION_TYPES) {
            OnlineDatabase.Document receipt = reactionReceipt(type, visit);
            if (receipt != null && !receipt.deleted) selected.add(type);
            if (reactionPending(type, visit)) waiting.add(type);
        }
        OnlineDatabase.Draft ownDraft = draft("QUESTION_DRAFT", studentView.draftId());
        if (ownDraft != null) studentView.acceptDraft(ownDraft.id, ownDraft.closed);
        boolean open = session.optBoolean("questionsOpen", true);
        studentView.update(session.optString("title"), session.optString("status"), outline, selected, waiting, open, live && open);
        LinearLayout records = studentView.records();
        records.removeAllViews();
        if (!notice.isEmpty()) ui.note(records, notice);
        renderSessionRecords(records, false);
        boolean hasRecords = !kind("QUESTION").isEmpty() || !kind("PUBLIC_QNA").isEmpty() || !kind("MARK").isEmpty()
                || !conflicts.isEmpty() || !notice.isEmpty();
        for (OnlineDatabase.Draft draft : drafts)
            if (visibleQuestionDraft(draft) && !studentView.editingDraft(draft.id)) hasRecords = true;
        for (OnlineDatabase.Outbox operation : operations)
            if (screen.lessonId.equals(operation.sessionId) && !"ACKED".equals(operation.state)
                    && !"SUPERSEDED".equals(operation.errorCode)
                    && !OnlineRepository.questionDraftKey(screen.lessonId, studentView.draftId()).equals(operation.entityKey))
                hasRecords = true;
        records.setVisibility(hasRecords ? View.VISIBLE : View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 종료된 수업의 복습 메뉴. 교수자는 1→2→3 단계로, 학생은 원문·요약을 따로 연다. */
    private void reviewHub(boolean teacher) {
        if (teacher) {
            ui.section(shell.content, "수업 후 자료 만들기",
                    "한 단계씩 끝내면 다음 단계 화면으로 넘어가요.");
            ui.card(shell.content, "1. 녹음·전체 원문 검토", "녹음 업로드, STT 변환, 오인식 수정 후 원문을 공개해요.",
                    "1단계", () -> open("online_material"));
            ui.card(shell.content, "2. 대주제별 요약·보충 설명", "원문을 공개한 뒤 대주제마다 요약과 보충 설명을 공개해요.",
                    "2단계", () -> open("online_summary"));
            ui.card(shell.content, "3. 복습 퀴즈 만들기", "직접 쓰거나 AI 초안으로 문제를 만들어 공개해요.",
                    "3단계", () -> open("online_quizzes"));
            ui.note(shell.content, "공개 Q&A는 이 화면 아래쪽에서 대주제별로 확인할 수 있어요.");
            return;
        }
        ui.section(shell.content, "수업 후 복습", "전체 원문과 요약을 나눠서 보고, 퀴즈로 확인해요.");
        ui.card(shell.content, "전체 강의 원문", "수업 처음부터 끝까지 구간별로 읽어요.",
                "원문", () -> open("online_transcript"));
        ui.card(shell.content, "대주제별 요약·보충 설명", "핵심만 빠르게 다시 봐요.",
                "요약", () -> open("online_summary"));
        ui.card(shell.content, "복습 퀴즈 풀기", "내 속도로 풀고 해설을 확인해요.",
                "퀴즈", () -> open("online_quizzes"));
        ui.card(shell.content, "내 질문·표시 기록", "궁금했던 순간과 작성 중인 질문을 다시 찾아요.",
                "나만 보는 기록", () -> open("online_records", screen.forLesson(screen.lessonId)));
        ui.note(shell.content, "공개 Q&A는 이 화면 아래쪽에서 대주제별로 확인할 수 있어요.");
    }

    private void renderReactionPages(List<OnlineDatabase.Document> outline, String currentTopic) {
        List<Ui.ReactionPage> pages = new ArrayList<>();
        JSONObject current = item("TOPIC", currentTopic);
        String currentRoot = current.isNull("parentTopicId") ? currentTopic : current.optString("parentTopicId");
        for (OnlineDatabase.Document root : outline) {
            if (!OnlineJson.object(root.json).isNull("parentTopicId")) continue;
            Ui.ReactionPage page = new Ui.ReactionPage(root.id,
                    (pages.size() + 1) + ". " + topicTitle(root.id), root.id.equals(currentRoot));
            for (OnlineDatabase.Document topic : outline) {
                JSONObject entry = OnlineJson.object(topic.json);
                if (!root.id.equals(topic.id) && !root.id.equals(entry.optString("parentTopicId"))) continue;
                JSONObject counts = item("STATS", "topic_" + topic.id).optJSONObject("eventCountsByType");
                if (counts == null) continue;
                for (int i = 0; i < Models.REACTION_TYPES.size(); i++)
                    page.counts[i] += counts.optInt(Models.REACTION_TYPES.get(i));
            }
            pages.add(page);
        }
        if (!currentRoot.isEmpty() && !currentRoot.equals(lastReactionProgress)) {
            reactionTopicId = currentRoot;
            lastReactionProgress = currentRoot;
        }
        ui.reactionPages(shell.content, pages, reactionTopicId, id -> reactionTopicId = id);
    }

    private OnlineDatabase.Document reactionReceipt(String type, String visitId) {
        for (OnlineDatabase.Document document : docs) {
            if (!"OWN_REACTION".equals(document.kind)) continue;
            JSONObject receipt = OnlineJson.object(document.json);
            if (visitId.equals(receipt.optString("visitId")) && type.equals(receipt.optString("type"))) {
                return document;
            }
        }
        return null;
    }

    private boolean reactionPending(String type, String visitId) {
        String key = OnlineRepository.reactionKey(screen.lessonId, visitId, type);
        for (OnlineDatabase.Outbox operation : operations) {
            if (key.equals(operation.entityKey) && !"ACKED".equals(operation.state)
                    && !"REJECTED".equals(operation.state)) return true;
        }
        return false;
    }

    private void renderOutline(List<OnlineDatabase.Document> outline, JSONObject progress, boolean canRevisit) {
        if (outline.isEmpty()) return;
        List<LessonOutlineView.Topic> topics = new ArrayList<>();
        String current = progress.isNull("currentVisitId") ? "" : progress.optString("currentTopicId");
        for (OnlineDatabase.Document document : outline) {
            JSONObject entry = OnlineJson.object(document.json);
            topics.add(new LessonOutlineView.Topic(document.id,
                    entry.isNull("parentTopicId") ? null : entry.optString("parentTopicId"),
                    entry.optString("title"), OnlineJson.contains(progress.optJSONArray("completedTopicIds"), document.id),
                    document.id.equals(current)));
        }
        LessonOutlineView.add(shell.content, topics, canRevisit ? id -> progress("REVISIT", id) : null);
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

    private void react(String type, JSONObject p) {
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
        if (reactionPending(type, visitId)) return;
        OnlineDatabase.Document receipt = reactionReceipt(type, visitId);
        boolean selected = receipt == null || receipt.deleted;
        repo.setReaction(screen.lessonId, p.optString("currentTopicId"), visitId, type,
                selected, receipt == null ? 0 : receipt.revision, callback(v -> {
                    if (shell != null) shell.dock.announceForAccessibility(
                            selected ? "익명 반응을 보냈어요." : "반응을 취소했어요.");
                }));
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
