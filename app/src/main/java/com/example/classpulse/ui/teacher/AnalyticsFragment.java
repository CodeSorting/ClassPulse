package com.example.classpulse.ui.teacher;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.classpulse.R;
import com.example.classpulse.domain.LessonAnalytics;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.example.classpulse.ui.Ui;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** v6 P33: evidence for the teacher to review explanations, without grading silence. */
public final class AnalyticsFragment extends BaseFragment {
    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "주제별 반응 분석", true);
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
            ui.note(content, "수업 정보를 불러오고 있어요.");
            return;
        }
        if (!vm.teacher() || !vm.userId().equals(lesson.teacherId)) {
            ui.note(content, "이 수업의 교수자만 반응 분석을 볼 수 있어요.");
            return;
        }
        LessonAnalytics stats = LessonAnalytics.from(lesson);
        ui.heading(content, "오늘 설명을 돌아봐요", lesson.title + " · 전체 수업 집계");
        ui.stats(
                content,
                "반응한 학생",
                stats.respondingStudents + " / " + stats.participants + "명",
                "제출 질문",
                stats.submittedQuestions + "개");
        ui.note(
                content,
                "반응 수와 반응한 학생 수는 달라요. 한 학생이 여러 반응을 남길 수 있고, 재설명 중에도 반응할 수 있어요. 반응이 없다고 이해했다고 판단하지 않아요.");
        ui.heading(content, "대주제별로 살펴봐요", "반응 비율은 참여 학생 중 해당 반응을 한 번 이상 남긴 학생의 비율이에요.");
        String[] labels = {"이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요"};
        for (LessonAnalytics.TopicStats topic : stats.topics) {
            ui.card(
                    content,
                    topic.title,
                    "반응 "
                            + topic.reactionEvents
                            + "건 · 반응한 학생 "
                            + topic.respondingStudents
                            + "명 · 미답변 "
                            + topic.unanswered
                            + "개",
                    "제출 질문 " + topic.questions + "개",
                    () -> navigate("questions", screen().withTopic(topic.topicId)));
            for (int i = 0; i < labels.length; i++) {
                int count = topic.studentsFor(LessonAnalytics.TYPES.get(i));
                String ratio =
                        stats.participants == 0
                                ? "참여 학생 없음"
                                : Math.round(count * 100f / stats.participants) + "%";
                ui.text(content, labels[i] + "  " + count + "명 · " + ratio);
            }
            ui.button(
                    content,
                    "이 대주제 질문 확인",
                    false,
                    () -> navigate("questions", screen().withTopic(topic.topicId)));
            ui.divider(content);
        }
        ui.heading(content, "설명한 순서", "재설명 구간도 이전 이력과 함께 남아요. 시간은 이 기기에 기록된 진도 변경 기준입니다.");
        List<Models.Visit> visits = new ArrayList<>(lesson.visits);
        visits.sort(Comparator.comparingLong(visit -> visit.startedAt));
        if (visits.isEmpty()) ui.note(content, "아직 수업을 시작하지 않았어요.");
        for (Models.Visit visit : visits) {
            int events = 0;
            for (Models.Reaction reaction : lesson.reactions)
                if (visit.id.equals(reaction.visitId)) events++;
            String duration =
                    visit.endedAt > 0 ? duration(visit.endedAt - visit.startedAt) : "진행 중";
            ui.card(
                    content,
                    Ui.topicTitle(lesson, visit.topicId),
                    Ui.time(visit.startedAt) + " · " + duration + " · 반응 " + events + "건",
                    visit.revisit ? "재설명" : "설명",
                    null);
        }
        ui.button(dock, "보충 설명 작성", true, () -> navigate("material_edit"));
        ui.button(
                dock,
                "수업으로 돌아가기",
                false,
                () -> navigate("ENDED".equals(lesson.status) ? "review" : "live"));
    }

    private static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return seconds < 60 ? seconds + "초" : (seconds / 60) + "분 " + (seconds % 60) + "초";
    }
}
