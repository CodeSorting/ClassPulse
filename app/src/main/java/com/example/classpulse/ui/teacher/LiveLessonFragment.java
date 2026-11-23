package com.example.classpulse.ui.teacher;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.classpulse.R;
import com.example.classpulse.data.LessonRepository;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.example.classpulse.ui.Ui;
import com.example.classpulse.ui.StudentLessonView;
import com.example.classpulse.ui.LessonOutlineView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared lesson view: teacher owns progress, student owns private context and reactions. */
public final class LiveLessonFragment extends BaseFragment {
    private boolean pending;
    private String reactionTopicId = "", lastReactionProgress = "";
    private StudentLessonView studentView;
    private Bundle studentState;
    private boolean studentLayout;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (state != null) {
            studentState = state.getBundle("studentLesson");
            reactionTopicId = state.getString("reactionTopicId", "");
            lastReactionProgress = state.getString("lastReactionProgress", "");
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("reactionTopicId", reactionTopicId);
        state.putString("lastReactionProgress", lastReactionProgress);
        state.putBundle("studentLesson", studentView == null ? studentState : studentView.saveState());
    }

    @Override
    public void onStop() {
        if (studentView != null) studentView.stop();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (studentView != null) studentView.release();
        studentLayout = false;
        super.onDestroyView();
    }

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
        if (shell == null) return;
        Models.Lesson lesson = lesson();
        if (lesson != null && !"TEACHER".equals(vm.role()) && lesson.isMember(vm.userId())
                && Models.LIVE.equals(lesson.status)) {
            renderStudentLesson(lesson);
            return;
        }
        if (studentLayout) {
            studentView.release();
            studentLayout = false;
        }
        content.setPadding(dp(24), dp(22), dp(24), dp(24));
        dock.setPadding(dp(24), dp(12), dp(24), dp(12));
        clear();
        if (lesson == null) {
            ui.note(content, "수업을 불러오고 있어요.");
            return;
        }
        boolean teacher = "TEACHER".equals(vm.role());
        if (teacher && !"ENDED".equals(lesson.status)) {
            content.setPadding(dp(12), dp(10), dp(12), dp(16));
            dock.setPadding(dp(12), dp(8), dp(12), dp(10));
        }
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
            ui.lessonHeader(content, lesson.title, lesson.status);
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
        Models.Topic current = lesson.currentVisitId == null ? null : lesson.currentTopic();
        ui.lessonHeader(content, lesson.title, lesson.status);
        if (lesson.resumeTopicId != null && !lesson.resumeTopicId.isEmpty())
            ui.note(content, "완료한 주제 재설명 중");
        else if (current == null) ui.note(content, "모든 주제를 마쳤어요");
        showTopics(lesson, teacher);
        if (teacher) renderTeacher(lesson, current);
        else renderStudentLesson(lesson);
    }

    private void renderStudentLesson(Models.Lesson lesson) {
        if (studentView == null) {
            final String questionStudent = vm.userId(), questionSession = lesson.id;
            studentView = new StudentLessonView(requireContext(), studentState, new StudentLessonView.Actions() {
                public void react(String type) {
                    Models.Lesson current = lesson();
                    if (current != null) LiveLessonFragment.this.react(current, type);
                }

                public void save(String id, String topic, String text, boolean submit, StudentLessonView.Result result) {
                    LessonRepository.Callback<String> callback = new LessonRepository.Callback<String>() {
                        public void onSuccess(String value) { result.success(); }
                        public void onError(String message) { result.error(message); }
                    };
                    if (submit) vm.repo.submitQuestion(id, questionSession, topic, questionStudent, text, callback);
                    else vm.repo.saveDraft(id, questionSession, topic, questionStudent, text, callback);
                }

                public void edit(String id, String topic) {
                    if (getView() != null) navigate("question_edit", screen().withQuestion(id).withTopic(topic));
                }

                public void error(String message) {
                    if (getView() != null) showError(message);
                }
            });
        }
        if (!studentLayout) {
            clear();
            content.setPadding(dp(12), dp(10), dp(12), dp(16));
            dock.setPadding(dp(12), dp(8), dp(12), dp(10));
            studentView.attach(content, dock);
            studentLayout = true;
        }
        List<LessonOutlineView.Topic> outline = new ArrayList<>();
        for (Models.Topic topic : lesson.topics)
            outline.add(new LessonOutlineView.Topic(topic.id, topic.parentTopicId, topic.title, topic.completed,
                    lesson.currentVisitId != null && topic.id.equals(lesson.currentTopicId)));
        Set<String> selected = new HashSet<>();
        for (Models.Reaction reaction : lesson.reactions)
            if (vm.userId().equals(reaction.studentId) && safeEquals(reaction.visitId, lesson.currentVisitId))
                selected.add(reaction.type);
        Models.Question ownDraft = lesson.question(studentView.draftId());
        if (ownDraft != null) studentView.acceptDraft(ownDraft.id, !Models.DRAFT.equals(ownDraft.status));
        studentView.update(lesson.title, lesson.status, outline, selected, java.util.Collections.emptySet(),
                lesson.questionsOpen, lesson.questionsOpen && lesson.currentVisitId != null);
        studentView.records().removeAllViews();
        int count = 0;
        for (Models.Question question : lesson.questions)
            if (vm.userId().equals(question.studentId) && !studentView.editingDraft(question.id)) count++;
        if (count > 0) ui.button(studentView.records(), "내 질문·답변", false, () -> navigate("questions"));
        studentView.records().setVisibility(count == 0 ? View.GONE : View.VISIBLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showTopics(Models.Lesson lesson, boolean teacher) {
        List<LessonOutlineView.Topic> outline = new ArrayList<>();
        for (Models.Topic topic : lesson.topics)
            outline.add(new LessonOutlineView.Topic(topic.id, topic.parentTopicId, topic.title, topic.completed,
                    Models.LIVE.equals(lesson.status) && lesson.currentVisitId != null
                            && topic.id.equals(lesson.currentTopicId)));
        LessonOutlineView.add(content, outline, teacher && Models.LIVE.equals(lesson.status)
                ? id -> mutate(cb -> vm.repo.revisitTopic(lesson.id, id, cb)) : null);
    }

    private void renderTeacher(Models.Lesson lesson, Models.Topic current) {
        List<Ui.ReactionPage> pages = new ArrayList<>();
        String currentRoot = current == null ? "" : current.parentTopicId == null ? current.id : current.parentTopicId;
        for (Models.Topic root : lesson.children(null)) {
            Ui.ReactionPage page = new Ui.ReactionPage(root.id, lesson.topicLabel(root), root.id.equals(currentRoot));
            for (Models.Reaction reaction : lesson.reactions) {
                Models.Topic topic = lesson.topic(reaction.topicId);
                if (topic == null || !lesson.isMember(reaction.studentId)
                        || (!root.id.equals(topic.id) && !root.id.equals(topic.parentTopicId))) continue;
                int index = Models.REACTION_TYPES.indexOf(reaction.type);
                if (index >= 0) page.counts[index]++;
            }
            pages.add(page);
        }
        if (!currentRoot.isEmpty() && !currentRoot.equals(lastReactionProgress)) {
            reactionTopicId = currentRoot;
            lastReactionProgress = currentRoot;
        }
        ui.section(content, "대주제별 학생 반응", "좌우로 넘겨 확인해요. 재설명 반응도 해당 대주제에 누적돼요.");
        ui.reactionPages(content, pages, reactionTopicId, id -> reactionTopicId = id);
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
                        : current == null || current.completed ? "모든 주제 완료 · 수업 마치기"
                        : hasNextTopic(lesson, current) ? "완료하고 다음 주제"
                        : current.parentTopicId == null ? "마지막 대주제 완료" : "마지막 소주제 완료";
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

    private boolean hasNextTopic(Models.Lesson lesson, Models.Topic current) {
        for (Models.Topic topic : lesson.teachingTopics())
            if (!topic.id.equals(current.id) && !topic.completed) return true;
        return false;
    }

    private void newQuestion(@Nullable String topicId) {
        navigate("question_edit", screen().withQuestion("").withTopic(topicId));
    }

    private void react(Models.Lesson lesson, String type) {
        vm.repo.toggleReaction(lesson.id, vm.userId(), lesson.currentVisitId, type,
                callback(selected -> {
                    if (dock != null) dock.announceForAccessibility(
                            selected ? "익명 반응을 저장했어요." : "반응을 취소했어요.");
                }));
    }

    private void chooseRevisit(Models.Lesson lesson) {
        List<Models.Topic> done = new ArrayList<>();
        for (Models.Topic t : lesson.teachingTopics())
            if (t.completed && !t.id.equals(lesson.currentTopicId)) done.add(t);
        if (done.isEmpty()) {
            showError("아직 다시 설명할 완료 주제가 없어요.");
            return;
        }
        String[] names = new String[done.size()];
        for (int i = 0; i < done.size(); i++) names[i] = lesson.topicPath(done.get(i));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("완료한 주제 재설명")
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
