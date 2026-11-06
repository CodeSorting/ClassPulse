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

public class ReviewFragment extends BaseFragment {
    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "수업 후 관리·복습", true);
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
            ui.note(content, "먼저 내 수업에서 수업을 선택해 주세요.");
            ui.button(dock, "내 수업으로", true, () -> navigate("home"));
            return;
        }
        boolean teacher = "TEACHER".equals(vm.role());
        if ((teacher && !vm.userId().equals(course.teacherId))
                || (!teacher && !course.isMember(vm.userId()))) {
            ui.note(content, "이 수업의 자료를 확인할 수 없어요. 참여 계정을 확인해 주세요.");
            return;
        }
        ui.heading(
                content,
                course.title,
                "ENDED".equals(course.status) ? "수업이 끝나도 질문과 복습은 이어갈 수 있어요." : "현재 진행 중인 수업이에요.");
        if (teacher) {
            int submitted = 0, unanswered = 0;
            for (Models.Question q : course.questions)
                if (!StudentSupport.draft(q)) {
                    submitted++;
                    if (!q.published) unanswered++;
                }
            ui.stats(
                    content, "접수 질문", String.valueOf(submitted), "미답변", String.valueOf(unanswered));
            ui.card(
                    content,
                    "주제별 반응 분석",
                    "어느 대주제에서 어려움과 질문이 많았는지 확인해요.",
                    "전체 수업 집계",
                    () -> navigate("analysis"));
            ui.card(
                    content,
                    "대주제별 질문·답변",
                    "접수된 질문과 공개 답변을 관리해요.",
                    "질문 관리",
                    () -> navigate("questions"));
            ui.card(
                    content,
                    "전체 원문·요약·보충 설명",
                    "직접 입력한 자료를 확인한 뒤 학생에게 공개해요.",
                    course.material != null && course.material.published ? "공개됨" : "비공개",
                    () -> navigate("material_edit"));
            ui.card(
                    content,
                    "복습 퀴즈 관리",
                    "직접 출제하고 공개할 문제를 검토해요.",
                    "수업 후 복습",
                    () -> navigate("quiz_list"));
            ui.note(content, "실제 녹음·STT·AI는 아직 연결되지 않았어요. 원문과 퀴즈를 직접 작성해 로컬 동작을 확인할 수 있어요.");
            ui.button(dock, "내 수업으로", true, () -> navigate("home"));
            return;
        }
        Models.Download download = StudentSupport.download(course, vm.userId());
        boolean available = course.material != null && course.material.published;
        if (available || download != null) {
            ui.card(
                    content,
                    "전체 강의 원문",
                    "처음부터 마지막 내용까지 확인해요.",
                    download == null ? "공개됨" : "기기에 저장됨",
                    () -> navigate("transcript"));
            ui.card(
                    content,
                    "요약·교수 보충 설명",
                    "전체 원문과 별도로 제공하는 복습 자료예요.",
                    "복습",
                    () -> navigate("transcript_summary"));
        } else {
            ui.card(content, "아직 공개된 강의 원문이 없어요", "교수님이 자료를 확인해 공개하면 이곳에서 볼 수 있어요.", "공개 대기", null);
            ui.note(content, "자료가 없어도 질문을 남기고 공개된 답변을 확인할 수 있어요.");
        }
        ui.card(
                content,
                "공개 질문과 답변",
                "교수님이 공개한 답변을 대주제별로 확인해요.",
                "Q&A",
                () -> navigate("questions"));
        ui.card(
                content,
                "복습 퀴즈",
                "내 속도로 풀고 저장된 응답과 해설을 확인해요.",
                "수업 후",
                () -> navigate("quiz_list"));
        ui.card(
                content,
                "내 반응·질문 기록",
                "궁금했던 순간과 작성 중인 질문을 다시 찾아요.",
                "나만의 기록",
                () -> navigate("records"));
        ui.button(
                dock,
                "질문 이어서 작성",
                true,
                () -> {
                    String topic =
                            course.topic(screen().topicId) != null
                                    ? screen().topicId
                                    : course.topics.isEmpty() ? "" : course.topics.get(0).id;
                    navigate("question_edit", screen().withQuestion("").withTopic(topic));
                });
        if (!"ENDED".equals(course.status))
            ui.button(dock, "수업 화면으로", false, () -> navigate("live"));
    }
}
