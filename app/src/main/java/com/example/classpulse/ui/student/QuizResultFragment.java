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
import java.util.List;

public class QuizResultFragment extends BaseFragment {
    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "내 응답·정답과 해설", true);
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
        if (course == null || !"STUDENT".equals(vm.role()) || !course.isMember(vm.userId())) {
            ui.note(content, "참여한 학생 계정에서 응답 기록을 볼 수 있어요.");
            return;
        }
        Models.Quiz quiz = course.quiz(screen().quizId);
        Models.Attempt attempt = StudentSupport.attempt(course, screen().quizId, vm.userId());
        if (quiz == null || attempt == null) {
            ui.note(content, "아직 제출한 응답이 없어요.");
            ui.button(dock, "퀴즈 목록으로", true, () -> navigate("quiz_list"));
            return;
        }
        String result =
                attempt.choice == -1 ? "모르겠어요로 응답했어요" : attempt.correct ? "정답이에요" : "설명을 다시 살펴봐요";
        ui.heading(content, result, Ui.topicTitle(course, quiz.topicId));
        if (attempt.quizVersion != quiz.version)
            ui.note(content, "이전 버전에 제출한 응답 기록이에요. 제출할 때의 문제와 해설을 보여드려요.");
        ui.card(
                content,
                StudentSupport.safe(attempt.question),
                "제출한 문제 · v" + attempt.quizVersion,
                "내 응답",
                null);
        List<String> options = attempt.options;
        String selected = attempt.choice == -1 ? "모르겠어요" : option(options, attempt.choice);
        ui.card(
                content,
                selected,
                "내가 선택한 응답 · " + Ui.time(attempt.createdAt),
                attempt.correct ? "정답" : "확인",
                null);
        ui.card(content, option(options, attempt.correctIndex), "정답", "해설", null);
        ui.text(
                content,
                StudentSupport.safe(attempt.explanation).isEmpty()
                        ? "이 문항에는 별도 해설이 없어요."
                        : attempt.explanation);
        ui.note(content, "한 문항의 결과만으로 이해도를 단정하지 않아요. 궁금한 부분은 대주제에 질문을 남길 수 있어요.");
        ui.button(
                dock,
                "관련 원문 확인",
                true,
                () -> {
                    navigate("transcript", screen().withTopic(quiz.topicId));
                });
        ui.button(dock, "퀴즈 목록으로", false, () -> navigate("quiz_list"));
    }

    private String option(List<String> values, int index) {
        return values != null && index >= 0 && index < values.size()
                ? (index + 1) + ". " + values.get(index)
                : "저장된 보기 정보가 없어요";
    }
}
