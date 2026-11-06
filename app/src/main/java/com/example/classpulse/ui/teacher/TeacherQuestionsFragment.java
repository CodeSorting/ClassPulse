package com.example.classpulse.ui.teacher;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.classpulse.R;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.example.classpulse.ui.Ui;

/** Teacher's submitted inbox; students receive only explicitly published Q&A. */
public final class TeacherQuestionsFragment extends BaseFragment {
    private String filter = "ALL", topicFilter;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "질문과 답변", true);
        if (state != null) {
            filter = state.getString("filter", "ALL");
            topicFilter = state.getString("topicFilter");
        } else if (topicFilter == null && !screen().topicId.isEmpty())
            topicFilter = screen().topicId;
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        observe(this::render);
    }

    private void render() {
        clear();
        Models.Lesson lesson = lesson();
        if (lesson == null) {
            ui.note(content, "수업을 불러오고 있어요.");
            return;
        }
        boolean teacher = "TEACHER".equals(vm.role());
        if (teacher && !vm.userId().equals(lesson.teacherId)) {
            ui.note(content, "이 수업의 교수자만 질문을 관리할 수 있어요.");
            return;
        }
        if (!teacher && !lesson.isMember(vm.userId())) {
            ui.note(content, "수업에 참여한 뒤 공개된 질문과 답변을 볼 수 있어요.");
            ui.button(dock, "수업 확인", true, () -> navigate("confirm_join"));
            return;
        }
        ui.heading(content, teacher ? "질문을 대주제별로 확인해요" : "교수님이 공개한 답변", lesson.title);
        if (teacher) {
            ui.pair(
                    content,
                    ("ALL".equals(filter) ? "✓ " : "") + "전체 질문",
                    () -> {
                        filter = "ALL";
                        render();
                    },
                    ("UNANSWERED".equals(filter) ? "✓ " : "") + "미답변",
                    () -> {
                        filter = "UNANSWERED";
                        render();
                    });
            ui.button(
                    content,
                    ("PUBLIC".equals(filter) ? "✓ " : "") + "공개한 답변",
                    false,
                    () -> {
                        filter = "PUBLIC";
                        render();
                    });
        }
        ui.pair(
                content,
                topicFilter == null ? "대주제로 찾기" : Ui.topicTitle(lesson, topicFilter),
                () ->
                        ui.chooseTopic(
                                lesson,
                                topicFilter,
                                topic -> {
                                    topicFilter = topic.id;
                                    render();
                                }),
                "전체 대주제",
                () -> {
                    topicFilter = null;
                    render();
                });
        int shown = 0;
        for (Models.Question question : lesson.questions) {
            if ("DRAFT".equals(question.status)) continue;
            boolean published =
                    question.published
                            && question.answer != null
                            && !question.answer.trim().isEmpty();
            if (!teacher && !published) continue;
            if (teacher && "PUBLIC".equals(filter) && !published) continue;
            if (teacher && "UNANSWERED".equals(filter) && published) continue;
            if (topicFilter != null && !topicFilter.equals(question.topicId)) continue;
            ui.card(
                    content,
                    question.text,
                    Ui.topicTitle(lesson, question.topicId) + " · " + Ui.time(question.createdAt),
                    published ? "답변 공개됨" : "답변 대기",
                    () -> {
                        navigate(
                                "question_detail",
                                screen().withQuestion(question.id).withTopic(question.topicId));
                    });
            shown++;
        }
        if (shown == 0)
            ui.note(
                    content,
                    teacher
                            ? "조건에 맞는 제출 질문이 아직 없어요. 학생의 개인 표시와 작성 중인 초안은 여기에 나타나지 않습니다."
                            : "아직 공개된 답변이 없어요. 내 질문의 상태는 내 기록에서 확인할 수 있어요.");
        if (teacher)
            ui.note(content, "답변 전 질문은 작성 학생과 교수자만 확인해요. 답변을 명시적으로 공개하면 수업 참여 학생이 함께 볼 수 있어요.");
        ui.button(
                dock,
                teacher ? "수업으로 돌아가기" : "내 질문 작성",
                true,
                () -> {
                    if (teacher) navigate("ENDED".equals(lesson.status) ? "review" : "live");
                    else {
                        navigate(
                                "question_edit",
                                screen().withQuestion("").withTopic(lesson.currentTopicId));
                    }
                });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("filter", filter);
        state.putString("topicFilter", topicFilter);
        super.onSaveInstanceState(state);
    }
}
