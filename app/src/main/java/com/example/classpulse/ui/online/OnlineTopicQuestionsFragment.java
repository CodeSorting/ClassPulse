package com.example.classpulse.ui.online;

import com.example.classpulse.online.OnlineDatabase;
import com.example.classpulse.online.OnlineJson;
import com.example.classpulse.online.OnlineRepository;
import org.json.JSONObject;

/** Topic-scoped server pagination keeps older questions reachable even beyond the first 30. */
public final class OnlineTopicQuestionsFragment extends OnlineFragment {
    private boolean loading, loaded, hasMore = true;
    private String cursor = "";
    private String renderedData;
    private long queryGeneration;
    @Override protected String title() { return "대주제 질문 목록"; }

    @Override protected void changed() {
        if (shell == null || repo == null) return;
        StringBuilder key = new StringBuilder(lesson().toString()).append(notice);
        for (OnlineDatabase.Document row : docs)
            if ("TOPIC".equals(row.kind) || "QUESTION".equals(row.kind)
                    && screen.topicId.equals(OnlineJson.object(row.json).optString("topicId"))) key.append(row.id).append(row.json);
        String value = key.toString();
        if (!value.equals(renderedData)) { renderedData = value; render(); }
    }

    @Override public void onStop() { queryGeneration++; loading = false; loaded = false; super.onStop(); }

    @Override protected void render() {
        if (shell == null || repo == null) return;
        clear();
        if (lesson().optString("id").isEmpty()) return;
        if (!owner()) { ui.note(shell.content, "이 수업의 교수자만 질문 분석을 볼 수 있어요."); return; }
        if (screen.topicId.isEmpty()) { ui.note(shell.content, "분석 화면에서 대주제를 선택해 주세요."); return; }
        shell.roleLabel.setText("교수자");
        ui.heading(shell.content, topicTitle(screen.topicId), "이 대주제에 접수된 질문");
        if (!loaded && !loading) { loaded = true; cursor = ""; load(); }
        int count = 0;
        for (OnlineDatabase.Document row : kind("QUESTION")) {
            JSONObject question = OnlineJson.object(row.json);
            if (!screen.topicId.equals(question.optString("topicId"))) continue;
            count++;
            ui.card(shell.content, question.optString("text"), stateLabel(question.optString("status")), "",
                    () -> open("online_question", screen.withQuestion(row.id)));
        }
        if (count == 0 && !loading) ui.note(shell.content, "이 대주제에 표시할 질문이 없어요.");
        if (loading) ui.note(shell.content, "질문을 불러오고 있어요.");
        if (hasMore) ui.button(shell.content, "이전 주제 질문 더 보기", false, this::load).setEnabled(!loading);
        ui.button(shell.content, "질문 새로고침", false, () -> { cursor = ""; hasMore = true; load(); }).setEnabled(!loading);
        ui.button(shell.content, "이 대주제 보충 설명", false, () -> open("online_material"));
        ui.button(shell.content, "이 대주제 복습 퀴즈", false, () -> open("online_quizzes"));
        showNotice();
    }

    private void load() {
        if (loading || !owner()) return;
        loading = true;
        long token = viewToken();
        long query = ++queryGeneration;
        repo.more(screen.lessonId, "TOPIC_QUESTION:" + screen.topicId, cursor, true, new OnlineRepository.Callback() {
            public void success(JSONObject value) {
                if (!current(token) || query != queryGeneration) return;
                loading = false;
                hasMore = value.optInt("count") == 30;
                cursor = value.optString("lastId");
                render();
            }
            public void error(String code, String message) {
                if (!current(token) || query != queryGeneration) return;
                loading = false;
                render();
                OnlineTopicQuestionsFragment.this.error(message);
            }
        });
        // A user-triggered refresh/page request must immediately disable the button.
        if (loaded) shell.getRoot().post(() -> { if (current(token) && loading) render(); });
    }
}
