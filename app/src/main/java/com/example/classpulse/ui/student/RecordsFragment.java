package com.example.classpulse.ui.student;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.example.classpulse.R;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.example.classpulse.ui.Ui;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RecordsFragment extends BaseFragment {
    private boolean reactions;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (state != null) reactions = state.getBoolean("reactions");
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "내 기록", true);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        observe(this::render);
    }

    private void render() {
        clear();
        if (!"STUDENT".equals(vm.role())) {
            ui.note(content, "학생 역할에서 개인 기록을 확인할 수 있어요.");
            return;
        }
        ui.pair(
                content,
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
        int count = 0;
        for (Models.Lesson course : vm.currentLessons()) {
            if (!course.isMember(vm.userId())) continue;
            boolean heading = false;
            if (reactions) {
                List<Models.Reaction> rows = new ArrayList<>();
                for (Models.Reaction item : course.reactions)
                    if (vm.userId().equals(item.studentId)) rows.add(item);
                rows.sort(Comparator.comparingLong((Models.Reaction r) -> r.createdAt).reversed());
                for (Models.Reaction item : rows) {
                    if (!heading) {
                        ui.heading(content, course.title, "내가 남긴 반응");
                        heading = true;
                    }
                    count++;
                    ui.card(
                            content,
                            StudentSupport.reaction(item.type),
                            Ui.topicTitle(course, item.topicId) + " · " + Ui.time(item.createdAt),
                            "기록됨",
                            () -> {
                                navigate(
                                        "review",
                                        screen().forLesson(course.id).withTopic(item.topicId));
                            });
                }
            } else {
                List<Models.Mark> marks = new ArrayList<>();
                for (Models.Mark item : course.marks)
                    if (vm.userId().equals(item.studentId)) marks.add(item);
                marks.sort(Comparator.comparingLong((Models.Mark m) -> m.createdAt).reversed());
                for (Models.Mark mark : marks) {
                    if (!heading) {
                        ui.heading(content, course.title, "나만 보는 표시와 제출한 질문");
                        heading = true;
                    }
                    count++;
                    ui.card(
                            content,
                            StudentSupport.safe(mark.keyword).trim().isEmpty()
                                    ? "궁금했던 순간"
                                    : mark.keyword,
                            Ui.topicTitle(course, mark.topicId) + " · " + Ui.time(mark.createdAt),
                            "개인 표시",
                            () -> showMark(course, mark));
                }
                List<Models.Question> questions = new ArrayList<>();
                for (Models.Question q : course.questions)
                    if (vm.userId().equals(q.studentId)) questions.add(q);
                questions.sort(
                        Comparator.comparingLong((Models.Question q) -> q.createdAt).reversed());
                for (Models.Question q : questions) {
                    if (!heading) {
                        ui.heading(content, course.title, "나만 보는 표시와 제출한 질문");
                        heading = true;
                    }
                    count++;
                    String badge =
                            StudentSupport.draft(q) ? "작성 중" : q.published ? "답변 공개됨" : "답변 대기";
                    ui.card(
                            content,
                            StudentSupport.safe(q.text).isEmpty() ? "작성 중인 질문" : q.text,
                            Ui.topicTitle(course, q.topicId) + " · " + Ui.time(q.createdAt),
                            badge,
                            () -> {
                                navigate(
                                        StudentSupport.draft(q)
                                                ? "question_edit"
                                                : "question_detail",
                                        screen().forLesson(course.id)
                                                .withQuestion(q.id)
                                                .withTopic(q.topicId));
                            });
                }
            }
        }
        if (count == 0)
            ui.heading(
                    content,
                    reactions ? "아직 남긴 반응이 없어요" : "아직 개인 기록이 없어요",
                    reactions
                            ? "수업 화면의 네 가지 반응으로 어려움을 전할 수 있어요."
                            : "질문 표시나 작성 중인 질문은 이곳에서 이어서 볼 수 있어요.");
        ui.note(content, "개인 표시·초안은 나만 볼 수 있어요. 교수님이 접수한 질문과 공개된 답변을 구분해 표시해요.");
        ui.button(dock, "내 수업으로", true, () -> navigate("home"));
    }

    private void showMark(Models.Lesson course, Models.Mark mark) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("개인 질문 표시")
                .setMessage(
                        Ui.topicTitle(course, mark.topicId)
                                + "\n"
                                + StudentSupport.safe(mark.keyword)
                                + "\n\n아직 교수님께 제출한 질문은 아니에요.")
                .setNegativeButton("닫기", null)
                .setPositiveButton(
                        "질문으로 이어 쓰기",
                        (dialog, which) ->
                                vm.repo.saveDraft(
                                        null,
                                        course.id,
                                        mark.topicId,
                                        vm.userId(),
                                        StudentSupport.safe(mark.keyword),
                                        callback(id -> {
                                            navigate(
                                                    "question_edit",
                                                    screen().forLesson(course.id)
                                                            .withQuestion(id)
                                                            .withTopic(mark.topicId));
                                        })))
                .show();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        out.putBoolean("reactions", reactions);
        super.onSaveInstanceState(out);
    }
}
