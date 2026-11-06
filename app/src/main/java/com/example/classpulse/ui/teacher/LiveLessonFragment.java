package com.example.classpulse.ui.teacher;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.classpulse.R;
import com.example.classpulse.data.LessonRepository;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.example.classpulse.ui.Ui;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared lesson view: teacher owns progress, student owns private context and reactions. */
public final class LiveLessonFragment extends BaseFragment {
    private static final String[] TYPES = {"UNDERSTANDING", "SPEED", "TERM", "CALCULATION"};
    private static final String[] LABELS = {"이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요"};
    private boolean pending;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "수업 진행", true);
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
            ui.note(content, "이 수업의 교수자 계정으로 확인해 주세요.");
            return;
        }
        if (!teacher && !lesson.isMember(vm.userId())) {
            ui.note(content, "수업 정보를 확인하고 먼저 참여해 주세요.");
            ui.button(dock, "수업 확인하기", true, () -> navigate("confirm_join"));
            return;
        }
        if ("ENDED".equals(lesson.status)) {
            ui.heading(content, "수업이 끝났어요", lesson.title);
            ui.note(content, "질문과 답변은 계속 이어갈 수 있어요. 공개된 자료와 퀴즈로 복습해 보세요.");
            ui.button(dock, teacher ? "수업 후 관리" : "수업 후 복습", true, () -> navigate("review"));
            return;
        }
        if ("READY".equals(lesson.status)) {
            ui.heading(content, "곧 수업이 시작돼요", lesson.title);
            showTopics(lesson, teacher);
            ui.note(
                    content,
                    teacher ? "준비가 되면 첫 대주제로 수업을 시작해 주세요." : "교수님이 수업을 시작하면 현재 대주제와 반응 버튼이 표시돼요.");
            ui.button(
                    dock,
                    teacher ? "초대·수업 시작" : "내 수업으로",
                    true,
                    () -> navigate(teacher ? "invite" : "home"));
            return;
        }
        Models.Topic current = lesson.currentTopic();
        ui.heading(
                content,
                current == null ? "모든 대주제를 마쳤어요" : (current.position + 1) + ". " + current.title,
                lesson.resumeTopicId == null || lesson.resumeTopicId.isEmpty()
                        ? "현재 진행 중인 대주제"
                        : "완료한 대주제 재설명 중");
        if (teacher) ui.note(content, "녹음·음성 변환은 아직 연동되지 않았어요. 현재는 수업 진도와 반응·질문을 이 기기에 저장합니다.");
        showTopics(lesson, teacher);
        if (teacher) renderTeacher(lesson, current);
        else renderStudent(lesson, current);
    }

    private void showTopics(Models.Lesson lesson, boolean teacher) {
        for (Models.Topic topic : lesson.topics) {
            String status =
                    topic.id.equals(lesson.currentTopicId) && "LIVE".equals(lesson.status)
                            ? "진행 중"
                            : topic.completed ? "완료" : "예정";
            ui.topic(
                    content,
                    (topic.position + 1) + ". " + topic.title,
                    status,
                    topic.id.equals(lesson.currentTopicId),
                    teacher ? null : () -> newQuestion(topic.id));
        }
    }

    private void renderTeacher(Models.Lesson lesson, Models.Topic current) {
        int[] counts = new int[4];
        Set<String> students = new HashSet<>();
        for (Models.Reaction reaction : lesson.reactions)
            if (safeEquals(reaction.visitId, lesson.currentVisitId)) {
                for (int i = 0; i < TYPES.length; i++)
                    if (TYPES[i].equals(reaction.type)) counts[i]++;
                students.add(reaction.studentId);
            }
        ui.heading(content, "지금 대주제의 익명 반응", "실제로 이 기기에서 전달한 반응만 집계해요.");
        ui.stats(
                content,
                LABELS[0],
                Integer.toString(counts[0]),
                LABELS[1],
                Integer.toString(counts[1]));
        ui.stats(
                content,
                LABELS[2],
                Integer.toString(counts[2]),
                LABELS[3],
                Integer.toString(counts[3]));
        int submitted = 0;
        for (Models.Question question : lesson.questions)
            if (!"DRAFT".equals(question.status)) submitted++;
        ui.pair(
                content,
                "질문 확인 · " + submitted,
                () -> navigate("questions"),
                "완료 주제 재설명",
                () -> chooseRevisit(lesson));
        ui.button(content, "대주제별 전체 반응 분석", false, () -> navigate("analysis"));
        String primary =
                lesson.resumeTopicId != null && !lesson.resumeTopicId.isEmpty()
                        ? "원래 진행 주제로 돌아가기"
                        : current == null || current.completed ? "모든 주제 완료 · 수업 마치기" : "완료하고 다음 주제";
        MaterialButton next =
                ui.button(
                        dock,
                        primary,
                        true,
                        () -> {
                            if (pending) return;
                            if (lesson.resumeTopicId != null && !lesson.resumeTopicId.isEmpty())
                                mutate(cb -> vm.repo.resumeTopic(lesson.id, cb));
                            else if (current == null || current.completed) end(lesson);
                            else mutate(cb -> vm.repo.completeTopic(lesson.id, cb));
                        });
        next.setEnabled(!pending);
        ui.button(dock, "수업 종료", false, () -> end(lesson)).setEnabled(!pending);
    }

    private void renderStudent(Models.Lesson lesson, Models.Topic current) {
        ui.pair(
                content,
                "질문 표시",
                () -> {
                    if (current == null) {
                        showError("질문할 대주제를 선택해 주세요.");
                        return;
                    }
                    ui.prompt(
                            "질문 키워드 · 선택",
                            "",
                            false,
                            value ->
                                    vm.repo.saveMark(
                                            lesson.id,
                                            current.id,
                                            vm.userId(),
                                            value,
                                            callback(id ->
                                                    Toast.makeText(
                                                                    requireContext(),
                                                                    "나만 볼 수 있는 질문 표시를 저장했어요.",
                                                                    Toast.LENGTH_SHORT)
                                                            .show())));
                },
                "질문 작성",
                () -> newQuestion(current == null ? null : current.id));
        ui.note(content, "완료된 대주제에도 질문할 수 있어요. 아래 반응은 지금 진행 중인 대주제에 연결됩니다.");
        ui.text(dock, "익명으로 지금의 이해 상태를 알려요");
        String[] labels = LABELS.clone();
        for (Models.Reaction r : lesson.reactions)
            if (vm.userId().equals(r.studentId) && safeEquals(r.visitId, lesson.currentVisitId))
                for (int i = 0; i < TYPES.length; i++)
                    if (TYPES[i].equals(r.type)) labels[i] = "✓ " + LABELS[i];
        ui.pair(
                dock,
                labels[0],
                () -> react(lesson, TYPES[0]),
                labels[1],
                () -> react(lesson, TYPES[1]));
        ui.pair(
                dock,
                labels[2],
                () -> react(lesson, TYPES[2]),
                labels[3],
                () -> react(lesson, TYPES[3]));
    }

    private void newQuestion(@Nullable String topicId) {
        navigate("question_edit", screen().withQuestion("").withTopic(topicId));
    }

    private void react(Models.Lesson lesson, String type) {
        vm.repo.react(
                lesson.id,
                vm.userId(),
                type,
                callback(v -> {
                    if (dock != null)
                        dock.announceForAccessibility("익명 반응을 저장했어요. 선택한 반응에 체크가 표시됩니다.");
                }));
    }

    private void chooseRevisit(Models.Lesson lesson) {
        List<Models.Topic> done = new ArrayList<>();
        for (Models.Topic t : lesson.topics)
            if (t.completed && !t.id.equals(lesson.currentTopicId)) done.add(t);
        if (done.isEmpty()) {
            showError("아직 다시 설명할 완료 주제가 없어요.");
            return;
        }
        String[] names = new String[done.size()];
        for (int i = 0; i < done.size(); i++) names[i] = done.get(i).title;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("완료한 대주제 재설명")
                .setItems(
                        names,
                        (d, which) ->
                                mutate(cb ->
                                        vm.repo.revisitTopic(lesson.id, done.get(which).id, cb)))
                .setNegativeButton("취소", null)
                .show();
    }

    private void end(Models.Lesson lesson) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("수업을 마칠까요?")
                .setMessage("진도 진행을 마쳐도 학생의 질문과 답변은 계속 열려 있어요. 녹음 파일이나 원문이 자동으로 생성되지는 않습니다.")
                .setNegativeButton("계속 수업", null)
                .setPositiveButton(
                        "수업 종료",
                        (d, w) -> {
                            if (pending || !isScreenActive()) return;
                            pending = true;
                            final long generation = viewGeneration();
                            vm.repo.endLesson(
                                    lesson.id,
                                    new LessonRepository.Callback<Void>() {
                                        @Override
                                        public void onSuccess(Void v) {
                                            pending = false;
                                            if (isCurrentView(generation))
                                                finishTo("review", screen());
                                        }

                                        @Override
                                        public void onError(String message) {
                                            pending = false;
                                            if (isCurrentView(generation)) {
                                                showError(message);
                                                render();
                                            }
                                        }
                                    });
                        })
                .show();
    }

    private interface Mutation {
        void run(LessonRepository.Callback<Void> callback);
    }

    private void mutate(Mutation mutation) {
        if (pending || !isScreenActive()) return;
        pending = true;
        final long generation = viewGeneration();
        mutation.run(
                new LessonRepository.Callback<Void>() {
                    @Override
                    public void onSuccess(Void v) {
                        pending = false;
                        if (isCurrentView(generation)) render();
                    }

                    @Override
                    public void onError(String message) {
                        pending = false;
                        if (isCurrentView(generation)) {
                            showError(message);
                            render();
                        }
                    }
                });
    }

    private static boolean safeEquals(String a, String b) {
        return a != null && a.equals(b);
    }
}
