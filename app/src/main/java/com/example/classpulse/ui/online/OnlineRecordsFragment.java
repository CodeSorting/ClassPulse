package com.example.classpulse.ui.online;

import android.os.Bundle;
import androidx.annotation.NonNull;
import com.example.classpulse.online.*;
import com.example.classpulse.ui.ScreenContext;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONObject;

/** Choose one enrolled lesson, then read its permitted questions and personal Room records. */
public final class OnlineRecordsFragment extends OnlineFragment {
    private boolean reactions;

    @Override
    protected String title() {
        return "내 기록";
    }

    @Override
    protected String navigationTab() {
        return "online_records";
    }

    @Override
    protected boolean observesHome() {
        return screen.lessonId.isEmpty();
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) reactions = saved.getBoolean("reactions");
    }

    @Override
    protected void render() {
        if (shell == null || repo == null) return;
        clear();
        if (screen.lessonId.isEmpty()) {
            ui.heading(shell.content, "수업별 기록을 확인해요", "수업을 선택하면 질문과 답변, 나만의 기록을 볼 수 있어요.");
            showNotice();
            if (sessions.isEmpty()) {
                ui.note(shell.content, "아직 참여한 수업이 없어요. 수업을 만들거나 초대로 참여해 주세요.");
                ui.button(shell.content, "수업으로", true, () -> host().openOnlineTab("online_home"));
            }
            for (OnlineDatabase.Session s : sessions)
                ui.card(
                        shell.content,
                        s.title,
                        repo.uid.equals(s.ownerUid) ? "담당 수업의 접수 질문과 공개 답변" : "내 질문 · 개인 표시 · 반응",
                        stateLabel(s.status),
                        () -> open("online_records", ScreenContext.empty().forLesson(s.id)));
            return;
        }
        JSONObject s = lesson();
        if (s.optString("id").isEmpty()) {
            ui.note(shell.content, "수업 접근 권한과 기록을 확인하고 있어요.");
            showNotice();
            return;
        }
        ui.heading(
                shell.content,
                s.optString("title"),
                owner() ? "담당 수업에 접수된 질문과 공개 답변이에요." : "개인 표시와 작성 중인 질문은 나만 볼 수 있어요.");
        showNotice();
        ui.button(shell.content, "다른 수업 기록", false, () -> host().openOnlineTab("online_records"));
        if (!owner())
            ui.pair(
                    shell.content,
                    reactions ? "질문·개인 표시" : "✓ 질문·개인 표시",
                    () -> {
                        reactions = false;
                        render();
                    },
                    reactions ? "✓ 내 반응" : "내 반응",
                    () -> {
                        reactions = true;
                        render();
                    });
        if (reactions && !owner()) {
            if (kind("OWN_REACTION").isEmpty()) ui.note(shell.content, "아직 남긴 반응이 없어요.");
            for (OnlineDatabase.Document d : kind("OWN_REACTION")) {
                JSONObject r = OnlineJson.object(d.json);
                String type = r.optString("type");
                String label =
                        "UNDERSTANDING".equals(type)
                                ? "이해가 안 돼요"
                                : "SPEED".equals(type)
                                        ? "너무 빨라요"
                                        : "TERM".equals(type) ? "용어가 어려워요" : "계산을 놓쳤어요";
                ui.card(
                        shell.content,
                        label,
                        topicTitle(r.optString("topicId")),
                        "내 반응",
                        () -> open("online_session", screen.forLesson(screen.lessonId)));
            }
        } else {
            ui.heading(shell.content, owner() ? "접수된 질문" : "내 질문", null);
            if (kind("QUESTION").isEmpty()) ui.note(shell.content, "아직 접수된 질문이 없어요.");
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
            if (!owner()) {
                ui.heading(shell.content, "작성 중인 질문과 개인 표시", null);
                int count = 0;
                for (OnlineDatabase.Draft d : drafts) {
                    if ("QUESTION_DRAFT".equals(d.kind)
                            && (!d.closed || "QUEUED".equals(d.state))) {
                        count++;
                        ui.card(
                                shell.content,
                                d.text.isEmpty() ? "작성 중인 질문" : d.text,
                                topicTitle(d.topicId),
                                d.closed ? "전송 대기" : d.dirty ? "이 기기에 저장됨" : "개인 초안",
                                () ->
                                        open(
                                                "online_question_edit",
                                                screen.withQuestion(d.id).withTopic(d.topicId)));
                    }
                    if ("MARK".equals(d.kind) && !"DELETED".equals(d.state)) {
                        count++;
                        ui.card(
                                shell.content,
                                d.text.isEmpty() ? "궁금했던 순간" : d.text,
                                topicTitle(d.topicId),
                                "개인 표시",
                                () ->
                                        new MaterialAlertDialogBuilder(requireContext())
                                                .setTitle("개인 질문 표시")
                                                .setMessage(topicTitle(d.topicId) + "\n" + d.text)
                                                .setNegativeButton("닫기", null)
                                                .setPositiveButton(
                                                        "질문으로 이어 쓰기",
                                                        (dialog, which) -> {
                                                            String id = OnlineJson.id();
                                                            repo.saveDraft(
                                                                    screen.lessonId,
                                                                    id,
                                                                    d.topicId,
                                                                    d.text,
                                                                    false,
                                                                    callback(v ->
                                                                            open(
                                                                                    "online_question_edit",
                                                                                    screen.withQuestion(
                                                                                                    id)
                                                                                            .withTopic(
                                                                                                    d.topicId))));
                                                        })
                                                .show());
                    }
                }
                if (count == 0) ui.note(shell.content, "아직 작성 중인 질문이나 개인 표시가 없어요.");
            }
            ui.heading(shell.content, "함께 보는 공개 Q&A", null);
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
        }
        ui.button(
                shell.content,
                "수업 화면으로",
                false,
                () -> open("online_session", screen.forLesson(screen.lessonId)));
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("reactions", reactions);
    }
}
