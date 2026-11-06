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

public final class QuestionDetailFragment extends BaseFragment {
    private EditText answerInput;
    private String editingQuestionId, draft;
    private boolean pending;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "질문과 답변", true);
        if (state != null) {
            editingQuestionId = state.getString("questionId");
            draft = state.getString("answerDraft");
        }
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        observe(this::render);
    }

    private void capture() {
        if (answerInput != null) draft = answerInput.getText().toString();
    }

    private void render() {
        capture();
        clear();
        answerInput = null;
        Models.Lesson lesson = lesson();
        if (lesson == null) {
            ui.note(content, "수업을 불러오고 있어요.");
            return;
        }
        Models.Question question = lesson.question(screen().questionId);
        if (question == null) {
            ui.note(content, "선택한 질문을 찾을 수 없어요.");
            ui.button(
                    dock,
                    "질문 목록으로",
                    true,
                    () -> navigate("TEACHER".equals(vm.role()) ? "questions" : "records"));
            return;
        }
        boolean teacher = "TEACHER".equals(vm.role());
        if (teacher && !vm.userId().equals(lesson.teacherId)) {
            ui.note(content, "이 수업의 교수자만 질문을 관리할 수 있어요.");
            return;
        }
        boolean publicAnswer =
                question.published && question.answer != null && !question.answer.trim().isEmpty();
        boolean own = vm.userId().equals(question.studentId);
        if ((!teacher && (!lesson.isMember(vm.userId()) || (!own && !publicAnswer)))
                || (teacher && "DRAFT".equals(question.status))) {
            ui.note(content, "이 질문을 볼 수 없어요. 제출 전 초안은 작성 학생만 볼 수 있습니다.");
            return;
        }
        if (!question.id.equals(editingQuestionId)) {
            editingQuestionId = question.id;
            draft = null;
        }
        ui.heading(content, Ui.topicTitle(lesson, question.topicId), "질문은 처음 선택한 대주제에 그대로 남아요.");
        ui.card(
                content,
                question.text,
                "익명 질문 · " + Ui.time(question.createdAt),
                "DRAFT".equals(question.status) ? "작성 중" : publicAnswer ? "답변 공개됨" : "답변 대기",
                null);
        if (publicAnswer)
            ui.card(content, "교수자 답변", question.answer, "공개본 v" + question.answerVersion, null);
        if (teacher) {
            if (draft == null)
                draft =
                        question.answerDraft != null && !question.answerDraft.isEmpty()
                                ? question.answerDraft
                                : question.answer == null ? "" : question.answer;
            answerInput = ui.field(content, publicAnswer ? "공개 답변 수정" : "답변 작성", draft, true);
            ui.button(content, "AI 답변 초안", false, () -> notConnected("AI 답변 초안"));
            ui.note(
                    content,
                    publicAnswer
                            ? "초안 저장만으로 학생에게 보이는 답변이 바뀌지 않아요. 내용을 확인한 뒤 수정본을 공개해 주세요."
                            : "초안은 아직 학생에게 보이지 않아요. 공개한 뒤 같은 수업 학생이 함께 볼 수 있어요.");
            ui.button(
                            dock,
                            publicAnswer ? "수정본 공개하기" : "답변 공개하기",
                            true,
                            () -> {
                                capture();
                                if (draft == null || draft.trim().isEmpty()) {
                                    answerInput.setError("답변을 입력해 주세요.");
                                    return;
                                }
                                new MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(publicAnswer ? "수정한 답변을 공개할까요?" : "이 답변을 공개할까요?")
                                        .setMessage("질문과 답변이 수업 참여 학생에게 익명으로 공개됩니다.")
                                        .setNegativeButton("계속 수정", null)
                                        .setPositiveButton("공개", (d, w) -> save(question, true))
                                        .show();
                            })
                    .setEnabled(!pending);
            ui.button(dock, "답변 초안 저장", false, () -> save(question, false)).setEnabled(!pending);
        } else {
            if (!publicAnswer)
                ui.note(content, "교수님이 아직 답변을 공개하지 않았어요. 수업이 끝난 뒤에도 여기에서 확인할 수 있어요.");
            else
                ui.pair(
                        content,
                        "연결 대주제 원문",
                        () -> {
                            navigate("transcript", screen().withTopic(question.topicId));
                        },
                        "공개 답변 목록",
                        () -> navigate("questions"));
            ui.button(
                    dock,
                    "같은 대주제로 질문",
                    true,
                    () -> {
                        navigate(
                                "question_edit",
                                screen().withQuestion("").withTopic(question.topicId));
                    });
            ui.button(dock, "내 기록으로", false, () -> navigate("records"));
        }
    }

    private void save(Models.Question question, boolean publish) {
        if (pending || !isScreenActive()) return;
        capture();
        pending = true;
        final long generation = viewGeneration();
        vm.repo.saveAnswer(
                question.id,
                draft == null ? "" : draft,
                publish,
                new LessonRepository.Callback<Void>() {
                    @Override
                    public void onSuccess(Void v) {
                        pending = false;
                        if (!isCurrentView(generation)) return;
                        Toast.makeText(
                                        requireContext(),
                                        publish ? "답변을 공개했어요." : "나만 보는 답변 초안을 저장했어요.",
                                        Toast.LENGTH_SHORT)
                                .show();
                        render();
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

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        capture();
        state.putString("questionId", editingQuestionId);
        state.putString("answerDraft", draft);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onDestroyView() {
        capture();
        answerInput = null;
        super.onDestroyView();
    }
}
