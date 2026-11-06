package com.example.classpulse.ui.student;

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

public class QuizListFragment extends BaseFragment {
    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "복습 퀴즈", true);
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
            ui.note(content, "퀴즈를 볼 수업을 먼저 선택해 주세요.");
            return;
        }
        boolean teacher = "TEACHER".equals(vm.role());
        if ((teacher && !vm.userId().equals(course.teacherId))
                || (!teacher && !course.isMember(vm.userId()))) {
            ui.note(content, "이 수업의 퀴즈를 확인할 수 없어요.");
            return;
        }
        ui.heading(
                content, course.title, teacher ? "직접 출제하고 검토한 퀴즈를 공개해요." : "제한 시간 없이 내 속도로 풀어보세요.");
        int count = 0;
        for (Models.Quiz quiz : course.quizzes) {
            if (!teacher && (!quiz.published || !"ENDED".equals(course.status))) continue;
            count++;
            Models.Attempt attempt = StudentSupport.attempt(course, quiz.id, vm.userId());
            String badge =
                    teacher
                            ? (quiz.published ? "공개됨" : "초안")
                            : (attempt == null ? "풀기 전" : "제출 완료");
            ui.card(
                    content,
                    StudentSupport.safe(quiz.title).isEmpty() ? quiz.question : quiz.title,
                    Ui.topicTitle(course, quiz.topicId),
                    badge,
                    () -> {
                        navigate(
                                teacher
                                        ? "quiz_edit"
                                        : attempt == null ? "quiz_take" : "quiz_result",
                                screen().withQuiz(quiz.id).withTopic(quiz.topicId));
                    });
            if (teacher && quiz.published) {
                int total = 0, correct = 0;
                for (Models.Attempt item : course.attempts)
                    if (quiz.id.equals(item.quizId)) {
                        total++;
                        if (item.correct) correct++;
                    }
                ui.text(content, "이 기기 응답 " + total + "개 · 정답 " + correct + "개");
            }
        }
        if (count == 0)
            ui.note(
                    content,
                    teacher
                            ? "아직 작성한 퀴즈가 없어요. 대주제를 선택해 첫 문제를 만들어 보세요."
                            : "아직 공개된 복습 퀴즈가 없어요. 원문과 공개 답변으로 먼저 복습할 수 있어요.");
        if (teacher) {
            if (!"ENDED".equals(course.status))
                ui.note(content, "퀴즈 초안은 준비할 수 있고, 공개는 수업을 마친 뒤 진행해요.");
            ui.button(
                    dock,
                    "복습 퀴즈 만들기",
                    true,
                    () -> {
                        navigate("quiz_edit", screen().withQuiz(""));
                    });
        }
        ui.button(dock, "복습 화면으로", false, () -> navigate("review"));
    }
}
