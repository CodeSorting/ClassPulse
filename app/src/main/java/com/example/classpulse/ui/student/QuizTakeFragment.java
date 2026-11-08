package com.example.classpulse.ui.student;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.example.classpulse.R;
import com.example.classpulse.data.LessonRepository;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.example.classpulse.ui.Ui;

public class QuizTakeFragment extends BaseFragment {
    private int choice = -2;
    private boolean submitting;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (state != null) choice = state.getInt("choice", -2);
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "복습 퀴즈 풀기", true);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        observe(this::render);
    }

    private void render() {
        clear();
        Models.Lesson course = lesson();
        if (course == null) {
            ui.note(content, "퀴즈를 다시 선택해 주세요.");
            return;
        }
        Models.Quiz quiz = course.quiz(screen().quizId);
        if (!"STUDENT".equals(vm.role())
                || !course.isMember(vm.userId())
                || quiz == null
                || !quiz.published
                || !"ENDED".equals(course.status)) {
            ui.note(content, "현재 참여 계정에서 풀 수 있는 공개 복습 퀴즈가 아니에요.");
            ui.button(dock, "퀴즈 목록으로", true, () -> navigate("quiz_list"));
            return;
        }
        if (StudentSupport.attempt(course, quiz.id, vm.userId()) != null) {
            ui.heading(content, "이미 제출한 응답이 있어요", "저장된 응답과 해설을 다시 확인할 수 있어요.");
            ui.button(dock, "내 응답·해설 보기", true, () -> navigate("quiz_result"));
            return;
        }
        ui.heading(content, Ui.topicTitle(course, quiz.topicId), quiz.title);
        ui.text(content, quiz.question);
        for (int index = 0; index < quiz.options.size(); index++) {
            final int option = index;
            ui.choice(
                    content,
                    (choice == option ? "✓ " : "") + (option + 1) + ". " + quiz.options.get(option),
                    choice == option,
                    () -> {
                        choice = option;
                        render();
                    });
        }
        ui.choice(
                content,
                choice == -1 ? "✓ 모르겠어요" : "모르겠어요",
                choice == -1,
                () -> {
                    choice = -1;
                    render();
                });
        ui.note(content, "선택만으로 제출되지는 않아요. 내용을 확인한 뒤 제출해 주세요.");
        ui.button(
                dock,
                submitting ? "응답 저장 중…" : "답안 제출하기",
                true,
                () -> {
                    if (submitting) return;
                    if (choice == -2) {
                        showError("답 또는 ‘모르겠어요’를 선택해 주세요.");
                        return;
                    }
                    String selected = choice == -1 ? "모르겠어요" : quiz.options.get(choice);
                    new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("이 응답을 제출할까요?")
                            .setMessage(selected)
                            .setNegativeButton("다시 보기", null)
                            .setPositiveButton(
                                    "제출",
                                    (dialog, which) -> {
                                        if (submitting || !isScreenActive()) return;
                                        submitting = true;
                                        final long generation = viewGeneration();
                                        render();
                                        vm.repo.submitAttempt(
                                                course.id,
                                                quiz.id,
                                                vm.userId(),
                                                choice,
                                                new LessonRepository.Callback<Void>() {
                                                    @Override
                                                    public void onSuccess(Void ignored) {
                                                        submitting = false;
                                                        if (isCurrentView(generation))
                                                            finishTo("quiz_result", screen());
                                                    }

                                                    @Override
                                                    public void onError(String message) {
                                                        submitting = false;
                                                        if (isCurrentView(generation)) {
                                                            showError(message);
                                                            render();
                                                        }
                                                    }
                                                });
                                    })
                            .show();
                });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        out.putInt("choice", choice);
        super.onSaveInstanceState(out);
    }
}
