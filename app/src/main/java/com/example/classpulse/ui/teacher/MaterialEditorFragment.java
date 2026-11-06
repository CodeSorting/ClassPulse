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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Keeps editable drafts separate from material already published to students. */
public final class MaterialEditorFragment extends BaseFragment {
    private EditText originalInput, summaryInput, supplementInput;
    private String original, summary, supplement, editingSession;
    private boolean initialized, pending;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "복습 자료 편집", true);
        initialized = false;
        if (state != null) {
            original = state.getString("original");
            summary = state.getString("summary");
            supplement = state.getString("supplement");
            editingSession = state.getString("editingSession");
        }
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        observe(this::initialize);
    }

    private void initialize() {
        Models.Lesson lesson = lesson();
        if (lesson == null) {
            if (!initialized) {
                clear();
                ui.note(content, "수업을 불러오고 있어요.");
            }
            return;
        }
        if (!"TEACHER".equals(vm.role()) || !vm.userId().equals(lesson.teacherId)) {
            clear();
            ui.note(content, "이 수업의 교수자만 자료를 편집할 수 있어요.");
            return;
        }
        if (initialized && lesson.id.equals(editingSession)) return;
        if (!lesson.id.equals(editingSession)) {
            original = null;
            summary = null;
            supplement = null;
        }
        editingSession = lesson.id;
        Models.Material material = lesson.material;
        if (original == null)
            original =
                    material == null
                            ? ""
                            : value(material.hasDraft ? material.draftOriginal : material.original);
        if (summary == null)
            summary =
                    material == null
                            ? ""
                            : value(material.hasDraft ? material.draftSummary : material.summary);
        if (supplement == null)
            supplement =
                    material == null
                            ? ""
                            : value(
                                    material.hasDraft
                                            ? material.draftSupplement
                                            : material.supplement);
        initialized = true;
        render(lesson);
    }

    private void render(Models.Lesson lesson) {
        clear();
        ui.heading(content, "교수 확인 후 공개해요", lesson.title);
        ui.note(
                content,
                "이번 로컬 버전에서는 원문을 직접 입력해요. 녹음·STT로 생성한 것처럼 처리하지 않습니다. 원문은 처음부터 마지막 수업 내용까지 포함해 주세요.");
        if (lesson.material != null && lesson.material.published)
            ui.card(
                    content,
                    "현재 공개된 자료",
                    "공개본 v" + lesson.material.version + " · 초안 저장은 공개본을 바꾸지 않아요.",
                    "공개됨",
                    null);
        originalInput = ui.field(content, "전체 강의 원문 · 필수", original, true);
        originalInput.setMinLines(9);
        summaryInput = ui.field(content, "요약 · 선택", summary, true);
        ui.button(content, "AI 요약 기능 안내", false, () -> notConnected("AI 요약"));
        supplementInput = ui.field(content, "교수자 보충 설명 · 선택", supplement, true);
        ui.note(content, "개별 질문에는 질문 상세에서 답변해 주세요. 이 보충 설명은 수업 참여 학생이 함께 읽는 일반 설명이에요.");
        if (!"ENDED".equals(lesson.status))
            ui.note(content, "수업 중에는 초안을 보관할 수 있어요. 수업을 마친 뒤 확인한 원문을 공개해 주세요.");
        ui.button(
                        dock,
                        lesson.material != null && lesson.material.published
                                ? "수정한 자료 공개"
                                : "검토한 자료 공개",
                        true,
                        () -> {
                            capture();
                            if (original.trim().isEmpty()) {
                                originalInput.setError("전체 원문을 입력해 주세요.");
                                originalInput.requestFocus();
                                return;
                            }
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("복습 자료를 공개할까요?")
                                    .setMessage(
                                            "전체 원문과 입력한 요약·보충 설명을 수업 참여 학생에게 공개합니다. 비어 있는 선택 자료는 공개하지 않아요.")
                                    .setNegativeButton("계속 수정", null)
                                    .setPositiveButton("공개", (d, w) -> save(true))
                                    .show();
                        })
                .setEnabled(!pending && "ENDED".equals(lesson.status));
        ui.button(dock, "초안 저장", false, () -> save(false)).setEnabled(!pending);
    }

    private void capture() {
        if (originalInput != null) original = originalInput.getText().toString();
        if (summaryInput != null) summary = summaryInput.getText().toString();
        if (supplementInput != null) supplement = supplementInput.getText().toString();
    }

    private void save(boolean publish) {
        if (pending || !isScreenActive() || !screen().lessonId.equals(editingSession)) return;
        capture();
        pending = true;
        final long generation = viewGeneration();
        vm.repo.saveMaterial(
                editingSession,
                value(original),
                value(summary),
                value(supplement),
                publish,
                new LessonRepository.Callback<Void>() {
                    @Override
                    public void onSuccess(Void v) {
                        pending = false;
                        if (!isCurrentView(generation)) return;
                        Toast.makeText(
                                        requireContext(),
                                        publish ? "복습 자료를 공개했어요." : "교수자 초안을 저장했어요.",
                                        Toast.LENGTH_SHORT)
                                .show();
                        if (publish) finishTo("review", screen());
                    }

                    @Override
                    public void onError(String message) {
                        pending = false;
                        if (isCurrentView(generation)) {
                            showError(message);
                            Models.Lesson lesson = lesson();
                            if (lesson != null) render(lesson);
                        }
                    }
                });
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        capture();
        state.putString("original", original);
        state.putString("summary", summary);
        state.putString("supplement", supplement);
        state.putString("editingSession", editingSession);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onDestroyView() {
        capture();
        originalInput = null;
        summaryInput = null;
        supplementInput = null;
        initialized = false;
        super.onDestroyView();
    }
}
