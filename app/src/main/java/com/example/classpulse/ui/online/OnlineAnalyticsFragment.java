package com.example.classpulse.ui.online;

import com.example.classpulse.online.OnlineDatabase;
import com.example.classpulse.online.OnlineJson;
import com.example.classpulse.ui.Ui;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONObject;

/** Uses server aggregates, never a partial page of questions or individual reaction records. */
public final class OnlineAnalyticsFragment extends OnlineFragment {
    private static final String[] TYPES = {"UNDERSTANDING", "SPEED", "TERM", "CALCULATION"};
    private static final String[] LABELS = {"이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요"};
    private String renderedData;

    @Override protected String title() { return "교수 수업 분석"; }

    @Override protected void changed() {
        if (shell == null || repo == null) return;
        StringBuilder key = new StringBuilder(lesson().toString()).append(notice);
        for (OnlineDatabase.Document row : docs)
            if ("TOPIC".equals(row.kind) || "STATS".equals(row.kind) || "VISIT".equals(row.kind)) key.append(row.id).append(row.json);
        String value = key.toString();
        if (!value.equals(renderedData)) { renderedData = value; render(); }
    }

    @Override protected void render() {
        if (shell == null || repo == null) return;
        clear();
        if (lesson().optString("id").isEmpty()) { ui.note(shell.content, "수업 정보를 확인하고 있어요."); return; }
        if (!owner()) { ui.note(shell.content, "이 수업의 교수자만 분석을 볼 수 있어요."); return; }
        shell.roleLabel.setText("교수자");
        showNotice();
        JSONObject summary = item("STATS", "summary");
        ui.heading(shell.content, lesson().optString("title"), "대주제별 반응과 질문 · 서버 집계");
        if (!summary.has("studentCount")) { ui.note(shell.content, "분석 자료를 불러오고 있어요."); return; }
        int participants = summary.optInt("studentCount");
        ui.stats(shell.content, "참여 학생", participants + "명", "반응한 학생", summary.optInt("uniqueReactingStudents") + "명");
        ui.stats(shell.content, "제출 질문", summary.optInt("submittedQuestionCount") + "개", "미답변 질문", summary.optInt("unansweredQuestionCount") + "개");
        ui.note(shell.content, "반응 건수와 학생 수는 달라요. 비율은 참여 학생 중 해당 반응을 남긴 학생의 비율이에요. 반응이 없다는 이유로 이해했다고 판단하지 않아요.");
        ui.section(shell.content, "대주제별 반응·질문", "질문이 많은 순서로 표시해요.");
        List<OnlineDatabase.Document> topics = new ArrayList<>(topics());
        topics.sort(Comparator.comparingInt((OnlineDatabase.Document topic) ->
                item("STATS", "topic_" + topic.id).optInt("submittedQuestionCount")).reversed());
        for (OnlineDatabase.Document topic : topics) {
            JSONObject stats = item("STATS", "topic_" + topic.id);
            ui.section(shell.content, topicTitle(topic.id), stats.has("reactionEventCount")
                    ? "반응 " + stats.optInt("reactionEventCount") + "건 · 반응한 학생 " + stats.optInt("uniqueReactingStudents")
                        + "명 · 질문 " + stats.optInt("submittedQuestionCount") + "개 · 미답변 " + stats.optInt("unansweredQuestionCount") + "개"
                    : "집계 불러오는 중…");
            if (stats.has("reactionEventCount")) {
                JSONObject people = stats.optJSONObject("uniqueStudentsByType");
                JSONObject events = stats.optJSONObject("eventCountsByType");
                for (int i = 0; i < TYPES.length; i++) {
                    int count = people == null ? 0 : people.optInt(TYPES[i]);
                    int total = events == null ? 0 : events.optInt(TYPES[i]);
                    String ratio = participants == 0 ? "참여 학생 없음" : Math.round(count * 100f / participants) + "%";
                    ui.meter(shell.content, LABELS[i], total + "건 · " + count + "명 · " + ratio, count, Math.max(1, participants));
                }
            }
            ui.button(shell.content, topicTitle(topic.id) + " · 질문 보기", false,
                    () -> open("online_topic_questions", screen.withTopic(topic.id)));
            ui.button(shell.content, topicTitle(topic.id) + " · 보충 설명", false,
                    () -> open("online_material", screen.withTopic(topic.id)));
            ui.button(shell.content, topicTitle(topic.id) + " · 복습 퀴즈", false,
                    () -> open("online_quizzes", screen.withTopic(topic.id)));
            ui.divider(shell.content);
        }
        ui.section(shell.content, "진도·재설명 이력", "서버에 기록한 설명 순서와 구간별 반응이에요.");
        List<OnlineDatabase.Document> visits = new ArrayList<>(kind("VISIT"));
        visits.sort(Comparator.comparingLong(v -> OnlineJson.object(v.json).optLong("startedAt")));
        if (visits.isEmpty()) ui.note(shell.content, "아직 설명 이력이 없거나 불러오는 중이에요.");
        for (OnlineDatabase.Document row : visits) {
            JSONObject visit = OnlineJson.object(row.json), stats = item("STATS", "visit_" + row.id);
            long start = visit.optLong("startedAt"), end = visit.optLong("endedAt");
            String duration = end > 0 ? Math.max(0, (end - start) / 1000) + "초" : "진행 중";
            String topicId = visit.optString("topicId");
            ui.card(shell.content, topicTitle(topicId) + (visit.optBoolean("revisit") ? " · 재설명" : " · 설명"),
                    Ui.time(start) + " · " + duration,
                    stats.has("reactionEventCount") ? "반응 " + stats.optInt("reactionEventCount") + "건 · "
                            + stats.optInt("uniqueReactingStudents") + "명" : "반응 집계 확인 중",
                    () -> open("online_topic_questions", screen.withTopic(topicId)));
        }
    }
}
