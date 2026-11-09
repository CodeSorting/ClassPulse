package com.example.classpulse.ui.online;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import com.example.classpulse.online.OnlineJson;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class OnlineQuizzesFragment extends OnlineLearningPage {
    private final List<JSONObject> items = new ArrayList<>();
    private String topic = "", quizTitle = "", quizId = "", attemptId = "";
    private long revision;
    private JSONObject quiz, result;
    private int index;
    private int[] answers;

    @Override protected String title() { return "복습 퀴즈"; }
    @Override protected String loadAction() { return "getLearningQuizzes"; }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null) return;
        topic = state.getString("topic", "");
        quizTitle = state.getString("quizTitle", "");
        revision = state.getLong("revision");
        quizId = state.getString("quizId", "");
        attemptId = state.getString("attemptId", "");
        index = state.getInt("index");
        answers = state.getIntArray("answers");
        try {
            loadItems(new JSONArray(state.getString("items", "[]")));
            if (state.containsKey("quiz")) quiz = new JSONObject(state.getString("quiz"));
            if (state.containsKey("result")) result = new JSONObject(state.getString("result"));
        } catch (JSONException ignored) {
            quiz = null;
            result = null;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("topic", topic);
        out.putString("quizTitle", quizTitle);
        out.putLong("revision", revision);
        out.putString("items", new JSONArray(items).toString());
        out.putString("quizId", quizId);
        out.putString("attemptId", attemptId);
        out.putInt("index", index);
        out.putIntArray("answers", answers);
        if (quiz != null) out.putString("quiz", quiz.toString());
        if (result != null) out.putString("result", result.toString());
    }

    @Override
    protected void render() {
        if (shell == null || repo == null) return;
        clear();
        JSONObject lesson = lesson();
        if (lesson.optString("id").isEmpty()) {
            ui.note(shell.content, "수업을 불러오고 있어요.");
            return;
        }
        boolean teacher = owner();
        shell.roleLabel.setText(teacher ? "교수자" : "학생");
        ui.heading(shell.content, lesson.optString("title"), teacher ? "대주제별 문제 작성과 공개" : "대주제별 복습과 풀이 기록");
        if (content == null) {
            ui.button(shell.content, loading ? "불러오는 중…" : "퀴즈 새로고침", false, this::reload).setEnabled(!loading);
            return;
        }
        if (sending) ui.note(shell.content, "서버 요청을 처리하고 있어요.");
        if (teacher) {
            if (topic.isEmpty()) showTeacherList();
            else showTeacherEditor();
        } else if (result != null) showResult();
        else if (quiz != null) showQuestion();
        else showStudentList();
    }

    private void showTeacherList() {
        ui.button(shell.content, "대주제 선택 · 퀴즈 작성", true, () -> chooseTopic(false, this::editTopic));
        JSONArray drafts = content.optJSONArray("drafts");
        if (drafts == null) return;
        for (int i = 0; i < drafts.length(); i++) {
            JSONObject draft = drafts.optJSONObject(i);
            if (draft == null) continue;
            String id = draft.optString("topicId");
            JSONObject published = find(content.optJSONArray("quizzes"), "topicId", id);
            String state = published.has("version") ? "공개 버전 " + published.optInt("version") : "초안";
            ui.button(shell.content, draft.optString("title") + " · " + state, false, () -> editTopic(id));
        }
    }

    private void editTopic(String id) {
        JSONObject saved = find(content.optJSONArray("drafts"), "topicId", id);
        topic = id;
        quizTitle = saved.optString("title", topicTitle(id) + " 복습");
        revision = saved.optLong("revision");
        loadItems(saved.optJSONArray("items"));
        render();
    }

    private void loadItems(JSONArray source) {
        items.clear();
        if (source != null) for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item != null) items.add(item);
        }
    }

    private void showTeacherEditor() {
        ui.section(shell.content, topicTitle(topic), "문제마다 보기 4개, 정답 하나와 해설을 입력해 주세요.");
        EditText title = field(shell.content, "퀴즈 제목", quizTitle, false, 100);
        title.setEnabled(!sending);
        title.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            public void onTextChanged(CharSequence value, int start, int before, int count) { quizTitle = value.toString(); }
            public void afterTextChanged(Editable value) {}
        });
        for (int i = 0; i < items.size(); i++) {
            int question = i;
            ui.button(shell.content, "문제 " + (i + 1) + " · " + items.get(i).optString("prompt"), false,
                    () -> editQuestion(question)).setEnabled(!sending);
        }
        ui.button(shell.content, "문제 직접 추가", false, () -> editQuestion(items.size()))
                .setEnabled(items.size() < 10 && !sending);
        ui.button(shell.content, "강의 자료로 AI 퀴즈 초안 만들기", false, this::confirmAi).setEnabled(!sending);
        ui.button(shell.content, "퀴즈 초안 저장", false, () -> saveQuiz(false)).setEnabled(!sending);
        ui.button(shell.content, "검토한 퀴즈 공개", true, () -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle("이 퀴즈를 공개할까요?")
                .setMessage("참여 학생은 퀴즈를 풀고 제출한 뒤 정답과 해설을 볼 수 있어요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("공개", (dialog, button) -> saveQuiz(true)).show())
                .setEnabled(!sending && "ENDED".equals(lesson().optString("status")));
        ui.button(shell.content, "퀴즈 목록", false, () -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle("편집을 닫을까요?")
                .setMessage("목록으로 돌아가면 마지막으로 저장한 초안을 사용해요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("목록", (dialog, button) -> returnToList()).show()).setEnabled(!sending);
    }

    private void confirmAi() {
        if (items.isEmpty()) generateQuiz();
        else new MaterialAlertDialogBuilder(requireContext()).setTitle("새 AI 초안을 만들까요?")
                .setMessage("생성에 성공하면 현재 편집 중인 문제를 AI 초안으로 교체해요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("만들기", (dialog, button) -> generateQuiz()).show();
    }

    private void generateQuiz() {
        request("generateLearningDraft", value -> { loadItems(value.optJSONArray("items")); render(); },
                "mode", "quiz", "topicId", topic);
    }

    private void saveQuiz(boolean publish) {
        if (quizTitle.trim().isEmpty() || items.isEmpty()) {
            error("퀴즈 제목과 문제를 입력해 주세요.");
            return;
        }
        request("saveLearningQuiz", value -> returnToList(), "operationId", OnlineJson.id(),
                "topicId", topic, "title", quizTitle, "items", new JSONArray(items),
                "baseRevision", revision, "publish", publish);
    }

    private void editQuestion(int question) {
        JSONObject item = question < items.size() ? items.get(question) : new JSONObject();
        LinearLayout fields = editorContent();
        EditText prompt = field(fields, "문제", item.optString("prompt"), true, 1000);
        EditText[] choices = new EditText[4];
        JSONArray existing = item.optJSONArray("choices");
        for (int i = 0; i < choices.length; i++)
            choices[i] = field(fields, "보기 " + (i + 1), existing == null ? "" : existing.optString(i), false, 500);
        ui.section(fields, "정답 선택", "");
        RadioGroup correct = radio(fields, new JSONArray(Arrays.asList("보기 1", "보기 2", "보기 3", "보기 4")),
                item.optInt("correctIndex", -1));
        EditText explanation = field(fields, "해설", item.optString("explanation"), true, 2000);
        MaterialAlertDialogBuilder builder = editor("문제 " + (question + 1), fields).setPositiveButton("반영", null);
        if (question < items.size()) builder.setNeutralButton("문제 빼기", (dialog, button) -> {
            items.remove(question);
            render();
        });
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            List<String> options = new ArrayList<>();
            for (EditText choice : choices) options.add(choice.getText().toString().trim());
            int answer = chosen(correct);
            if (answer < 0 || prompt.getText().toString().trim().isEmpty() ||
                    explanation.getText().toString().trim().isEmpty() || options.contains("") ||
                    new HashSet<>(options).size() != options.size()) {
                error("문제, 서로 다른 보기 4개, 정답과 해설을 입력해 주세요.");
                return;
            }
            JSONObject value = OnlineJson.values("prompt", prompt.getText().toString(), "choices", new JSONArray(options),
                    "correctIndex", answer, "explanation", explanation.getText().toString());
            if (question == items.size()) items.add(value);
            else items.set(question, value);
            dialog.dismiss();
            render();
        }));
        dialog.show();
    }

    private EditText field(LinearLayout parent, String label, String value, boolean multiline, int max) {
        EditText input = ui.field(parent, label, value, multiline);
        input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(max) });
        return input;
    }

    private void showStudentList() {
        JSONArray quizzes = content.optJSONArray("quizzes");
        if (quizzes == null || quizzes.length() == 0) ui.note(shell.content, "공개된 복습 퀴즈가 아직 없어요.");
        else for (int i = 0; i < quizzes.length(); i++) {
            JSONObject available = quizzes.optJSONObject(i);
            if (available != null) ui.button(shell.content, available.optString("title") + " · 풀기", true,
                    () -> take(available)).setEnabled(!sending);
        }
        JSONArray attempts = content.optJSONArray("attempts");
        if (attempts == null || attempts.length() == 0) return;
        ui.section(shell.content, "내 풀이 기록", "최근 풀이 결과를 다시 확인할 수 있어요.");
        for (int i = 0; i < attempts.length(); i++) {
            JSONObject saved = attempts.optJSONObject(i);
            if (saved != null) ui.button(shell.content, saved.optString("title") + " · " + saved.optInt("score") + "개 정답",
                    false, () -> { result = saved; render(); }).setEnabled(!sending);
        }
    }

    private void take(JSONObject available) {
        String id = available.optString("id");
        request("getLearningQuiz", value -> {
            JSONArray questions = value.optJSONArray("items");
            if (questions == null || questions.length() == 0) {
                render();
                error("풀 수 있는 퀴즈를 찾지 못했어요.");
                return;
            }
            quiz = value;
            quizId = id;
            attemptId = OnlineJson.id();
            index = 0;
            answers = new int[questions.length()];
            Arrays.fill(answers, -1);
            result = null;
            render();
            shell.scroll.scrollTo(0, 0);
        }, "quizId", id, "version", available.optInt("version"));
    }

    private void showQuestion() {
        JSONArray questions = quiz.optJSONArray("items");
        JSONObject item = questions == null ? null : questions.optJSONObject(index);
        if (item == null || answers == null) return;
        ui.section(shell.content, quiz.optString("title"), "문제 " + (index + 1) + " / " + questions.length());
        ui.text(shell.content, item.optString("prompt"));
        JSONArray choices = item.optJSONArray("choices");
        if (choices == null) return;
        RadioGroup group = radio(shell.content, choices, answers[index]);
        int question = index;
        group.setOnCheckedChangeListener((radio, id) -> answers[question] = chosen(radio));
        for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setEnabled(!sending);
        ui.button(shell.content, "이전 문제", false, () -> showQuestionAt(index - 1)).setEnabled(index > 0 && !sending);
        ui.button(shell.content, "다음 문제", false, () -> showQuestionAt(index + 1))
                .setEnabled(index + 1 < questions.length() && !sending);
        ui.button(shell.content, "풀이 제출", true, this::submitQuiz).setEnabled(!sending);
    }

    private void showQuestionAt(int question) {
        index = question;
        render();
        shell.scroll.scrollTo(0, 0);
    }

    private void submitQuiz() {
        JSONArray selected = new JSONArray();
        for (int answer : answers) {
            if (answer < 0) {
                error("모든 문제에 답한 뒤 제출해 주세요.");
                return;
            }
            selected.put(answer);
        }
        request("submitLearningQuiz", value -> {
            quiz = null;
            result = value;
            render();
            shell.scroll.scrollTo(0, 0);
        },
                "operationId", attemptId, "attemptId", attemptId, "quizId", quizId,
                "version", quiz.optInt("version"), "answers", selected);
    }

    private void showResult() {
        JSONArray graded = result.optJSONArray("items");
        if (graded == null) return;
        ui.section(shell.content, result.optString("title"), result.optInt("score") + " / " + graded.length() + "개 정답");
        for (int i = 0; i < graded.length(); i++) {
            JSONObject item = graded.optJSONObject(i);
            if (item == null || item.optJSONArray("choices") == null) continue;
            JSONArray choices = item.optJSONArray("choices");
            int selected = item.optInt("selected"), correct = item.optInt("correctIndex");
            ui.section(shell.content, (i + 1) + ". " + item.optString("prompt"), selected == correct ? "정답" : "오답");
            ui.text(shell.content, "내 답 · " + choices.optString(selected));
            ui.text(shell.content, "정답 · " + choices.optString(correct));
            ui.note(shell.content, item.optString("explanation"));
        }
        JSONObject available = find(content.optJSONArray("quizzes"), "id", result.optString("quizId"));
        if (available.has("version")) ui.button(shell.content, "다시 풀기", true,
                () -> take(available)).setEnabled(!sending);
        ui.button(shell.content, "퀴즈 목록으로", false, this::returnToList).setEnabled(!sending);
    }

    private void returnToList() {
        quiz = null;
        result = null;
        topic = "";
        reload();
        render();
    }

    private RadioGroup radio(LinearLayout parent, JSONArray labels, int selected) {
        RadioGroup group = new RadioGroup(requireContext());
        for (int i = 0; i < labels.length(); i++) {
            RadioButton button = new RadioButton(requireContext());
            button.setId(View.generateViewId());
            button.setText(labels.optString(i));
            group.addView(button);
            if (i == selected) group.check(button.getId());
        }
        parent.addView(group);
        return group;
    }

    private int chosen(RadioGroup group) {
        for (int i = 0; i < group.getChildCount(); i++)
            if (group.getChildAt(i).getId() == group.getCheckedRadioButtonId()) return i;
        return -1;
    }
}
