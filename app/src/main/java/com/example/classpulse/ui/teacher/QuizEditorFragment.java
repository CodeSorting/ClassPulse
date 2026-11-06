package com.example.classpulse.ui.teacher;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.classpulse.R;
import com.example.classpulse.data.LessonRepository;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.example.classpulse.ui.Ui;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.Arrays;

/** Single-choice manual quiz with an explicit teacher review and publication step. */
public final class QuizEditorFragment extends BaseFragment {
    private EditText titleInput, questionInput, explanationInput;
    private final ArrayList<EditText> optionInputs = new ArrayList<>();
    private ArrayList<String> options = new ArrayList<>(Arrays.asList("", "", "", ""));
    private String title = "", question = "", explanation = "", topicId, editingId, editingSession;
    private int correct = 0;
    private boolean initialized, pending;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "복습 퀴즈 편집", true);
        initialized = false;
        if (state != null) {
            title = state.getString("title", "");
            question = state.getString("question", "");
            explanation = state.getString("explanation", "");
            topicId = state.getString("topicId");
            editingId = state.getString("editingId");
            editingSession = state.getString("editingSession");
            correct = state.getInt("correct", 0);
            ArrayList<String> saved = state.getStringArrayList("options");
            if (saved != null) options = saved;
        }
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        observe(this::initialize);
    }

    private void initialize() {
        Models.Lesson lesson = lesson();
        if (lesson == null) {
            if (!initialized) {
                clear();
                ui.note(content, "수업을 불러오고 있어요.");
            }
            return;
        }
        if (!"TEACHER".equals(vm.role()) || !vm.userId().equals(lesson.teacherId)) {
            clear();
            ui.note(content, "수업의 교수자만 퀴즈를 편집할 수 있어요.");
            return;
        }
        if (initialized) return;
        String selected = screen().quizId;
        if (!lesson.id.equals(editingSession)) {
            if (!selected.isEmpty() && lesson.quiz(selected) == null) {
                clear();
                ui.note(content, "선택한 퀴즈를 찾을 수 없어요.");
                return;
            }
            editingSession = lesson.id;
            editingId = selected;
            title = "";
            question = "";
            explanation = "";
            correct = 0;
            options = new ArrayList<>(Arrays.asList("", "", "", ""));
            Models.Quiz quiz = lesson.quiz(selected);
            if (quiz != null) {
                title = quiz.hasDraft ? quiz.draftTitle : quiz.title;
                question = quiz.hasDraft ? quiz.draftQuestion : quiz.question;
                explanation = quiz.hasDraft ? quiz.draftExplanation : quiz.explanation;
                topicId = quiz.topicId;
                correct = quiz.hasDraft ? quiz.draftCorrectIndex : quiz.correctIndex;
                options = new ArrayList<>(quiz.hasDraft ? quiz.draftOptions : quiz.options);
            }
        }
        if (topicId == null || lesson.topic(topicId) == null)
            topicId =
                    lesson.currentTopicId != null
                            ? lesson.currentTopicId
                            : lesson.topics.isEmpty() ? null : lesson.topics.get(0).id;
        initialized = true;
        render(lesson);
    }

    private void capture() {
        if (titleInput != null) title = titleInput.getText().toString();
        if (questionInput != null) question = questionInput.getText().toString();
        if (explanationInput != null) explanation = explanationInput.getText().toString();
        if (!optionInputs.isEmpty()) {
            options.clear();
            for (EditText input : optionInputs) options.add(input.getText().toString());
        }
    }

    private void render(Models.Lesson lesson) {
        clear();
        optionInputs.clear();
        Models.Quiz existing = lesson.quiz(editingId);
        boolean answered = false;
        if (existing != null && existing.published)
            for (Models.Attempt attempt : lesson.attempts)
                if (existing.id.equals(attempt.quizId)) {
                    answered = true;
                    break;
                }
        ui.heading(content, "수업 후 복습 퀴즈", "학생이 자신의 시간에 풀 수 있는 문항을 직접 만들어요.");
        if (answered) {
            ui.note(content, "이미 제출된 답안이 있어 공개 문항은 바꾸지 않아요. 내용을 새 퀴즈로 복사해 수정할 수 있어요.");
            ui.button(
                    content,
                    "이 내용으로 새 퀴즈 만들기",
                    false,
                    () -> {
                        capture();
                        editingId = "";
                        title = title + " (새 문항)";
                        render(lesson);
                    });
        }
        ui.card(
                content,
                Ui.topicTitle(lesson, topicId),
                "출제할 대주제",
                null,
                () -> {
                    capture();
                    ui.chooseTopic(
                            lesson,
                            topicId,
                            t -> {
                                topicId = t.id;
                                render(lesson);
                            });
                });
        titleInput = ui.field(content, "퀴즈 제목", title, false);
        questionInput = ui.field(content, "문제", question, true);
        for (int i = 0; i < options.size(); i++)
            optionInputs.add(ui.field(content, "보기 " + (i + 1), options.get(i), false));
        ui.button(
                content,
                "정답 · 보기 " + (correct + 1),
                false,
                () -> {
                    capture();
                    String[] labels = new String[options.size()];
                    for (int i = 0; i < options.size(); i++)
                        labels[i] =
                                (i + 1)
                                        + ". "
                                        + (options.get(i).trim().isEmpty()
                                                ? "아직 입력하지 않음"
                                                : options.get(i));
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("정답 선택")
                            .setSingleChoiceItems(
                                    labels,
                                    correct,
                                    (d, which) -> {
                                        correct = which;
                                        d.dismiss();
                                        render(lesson);
                                    })
                            .setNegativeButton("취소", null)
                            .show();
                });
        explanationInput = ui.field(content, "정답 해설", explanation, true);
        ui.button(content, "AI 문항 초안 기능 안내", false, () -> notConnected("AI 복습 퀴즈 생성"));
        ui.note(content, "문제·보기·정답·해설을 확인한 뒤 공개해 주세요. 시연에서는 한 번 제출한 답안의 결과를 다시 확인할 수 있어요.");
        if (!"ENDED".equals(lesson.status))
            ui.note(content, "수업 중에는 초안을 저장할 수 있어요. 퀴즈 공개는 수업을 마친 뒤 가능합니다.");
        ui.button(
                        dock,
                        "검토하고 공개",
                        true,
                        () -> {
                            capture();
                            if (!validate(true)) return;
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("퀴즈를 공개할까요?")
                                    .setMessage(
                                            "대주제: "
                                                    + Ui.topicTitle(lesson, topicId)
                                                    + "\n정답: 보기 "
                                                    + (correct + 1)
                                                    + "\n학생은 수업 후 자신의 속도로 풀어요.")
                                    .setNegativeButton("계속 수정", null)
                                    .setPositiveButton("공개", (d, w) -> save(true))
                                    .show();
                        })
                .setEnabled(!pending && !answered && "ENDED".equals(lesson.status));
        ui.button(
                        dock,
                        "초안 저장",
                        false,
                        () -> {
                            capture();
                            if (validate(false)) save(false);
                        })
                .setEnabled(!pending);
    }

    private boolean validate(boolean publish) {
        if (topicId == null) {
            showError("출제할 대주제를 선택해 주세요.");
            return false;
        }
        if (title.trim().isEmpty() || question.trim().isEmpty()) {
            showError("퀴즈 제목과 문제를 입력해 주세요.");
            return false;
        }
        for (String option : options)
            if (option.trim().isEmpty()) {
                showError("모든 보기를 입력해 주세요.");
                return false;
            }
        if (publish && explanation.trim().isEmpty()) {
            showError("정답 해설을 입력해 주세요.");
            return false;
        }
        return true;
    }

    private void save(boolean publish) {
        if (pending || !isScreenActive() || !screen().lessonId.equals(editingSession)) return;
        capture();
        pending = true;
        final long generation = viewGeneration();
        vm.repo.saveQuiz(
                editingId,
                editingSession,
                topicId,
                title.trim(),
                question.trim(),
                options,
                correct,
                explanation.trim(),
                publish,
                new LessonRepository.Callback<String>() {
                    @Override
                    public void onSuccess(String id) {
                        pending = false;
                        editingId = id;
                        if (!isCurrentView(generation)) return;
                        Toast.makeText(
                                        requireContext(),
                                        publish ? "복습 퀴즈를 공개했어요." : "퀴즈 초안을 저장했어요.",
                                        Toast.LENGTH_SHORT)
                                .show();
                        finishTo("quiz_list", screen().withQuiz(""));
                    }

                    @Override
                    public void onError(String message) {
                        pending = false;
                        if (isCurrentView(generation)) {
                            showError(message);
                            Models.Lesson lesson = lesson();
                            if (lesson != null) render(lesson);
                        }
                    }
                });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        capture();
        state.putString("title", title);
        state.putString("question", question);
        state.putString("explanation", explanation);
        state.putString("topicId", topicId);
        state.putString("editingId", editingId);
        state.putString("editingSession", editingSession);
        state.putInt("correct", correct);
        state.putStringArrayList("options", options);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onDestroyView() {
        capture();
        titleInput = null;
        questionInput = null;
        explanationInput = null;
        optionInputs.clear();
        initialized = false;
        super.onDestroyView();
    }
}
