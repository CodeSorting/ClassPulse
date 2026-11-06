package com.example.classpulse.ui.student;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import com.example.classpulse.R;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.example.classpulse.ui.Ui;

public class TranscriptFragment extends BaseFragment {
    private int mode, savedScroll;
    private String jumpTopic;
    private boolean firstRender = true;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        mode = getArguments() == null ? 0 : getArguments().getInt("material_mode", 0);
        if (state != null) {
            mode = state.getInt("mode");
            savedScroll = state.getInt("scroll");
        }
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "강의 원문·복습 자료", true);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        observe(this::render);
    }

    private NestedScrollView scroll() {
        if (content == null) return null;
        ViewParent parent = content.getParent();
        while (parent != null) {
            if (parent instanceof NestedScrollView) return (NestedScrollView) parent;
            parent = parent.getParent();
        }
        return null;
    }

    private void render() {
        NestedScrollView oldScroll = scroll();
        int restoreScroll =
                firstRender ? savedScroll : oldScroll == null ? 0 : oldScroll.getScrollY();
        clear();
        Models.Lesson course = lesson();
        if (course == null) {
            ui.note(content, "수업을 먼저 선택해 주세요.");
            return;
        }
        boolean teacher = "TEACHER".equals(vm.role());
        if ((teacher && !vm.userId().equals(course.teacherId))
                || (!teacher && !course.isMember(vm.userId()))) {
            ui.note(content, "참여한 계정에서만 수업 자료를 볼 수 있어요.");
            return;
        }
        Models.Material material = course.material;
        Models.Download download = teacher ? null : StudentSupport.download(course, vm.userId());
        String original = "", summary = "", supplement = "";
        if (teacher && material != null) {
            original =
                    material.hasDraft
                            ? StudentSupport.safe(material.draftOriginal)
                            : StudentSupport.safe(material.original);
            summary =
                    material.hasDraft
                            ? StudentSupport.safe(material.draftSummary)
                            : StudentSupport.safe(material.summary);
            supplement =
                    material.hasDraft
                            ? StudentSupport.safe(material.draftSupplement)
                            : StudentSupport.safe(material.supplement);
            ui.note(
                    content,
                    material.hasDraft
                            ? "교수자 검토 중인 자료예요. 공개 전 초안은 학생에게 보이지 않아요."
                            : "현재 공개한 자료를 확인하고 있어요.");
        } else if (download != null) {
            original = StudentSupport.safe(download.original);
            summary = StudentSupport.safe(download.summary);
            supplement = StudentSupport.safe(download.supplement);
            ui.note(
                    content,
                    "기기에 저장한 자료 v" + download.version + " · " + Ui.time(download.downloadedAt));
            if (material != null && material.published && material.version > download.version) {
                ui.card(
                        content,
                        "새로 공개된 자료가 있어요",
                        "저장본은 그대로 유지됩니다. 새 공개본으로 갱신할 수 있어요.",
                        "v" + material.version,
                        () ->
                                vm.repo.downloadMaterial(
                                        course.id, vm.userId(), callback(value -> render())));
            }
        } else if (material != null && material.published) {
            original = StudentSupport.safe(material.original);
            summary = StudentSupport.safe(material.summary);
            supplement = StudentSupport.safe(material.supplement);
            ui.note(content, "교수님이 공개한 자료 v" + material.version + "예요. 기기에 저장해 복습할 수 있어요.");
        } else {
            ui.heading(content, "아직 공개된 원문이 없어요", "원문이 준비되는 동안에도 질문을 작성할 수 있어요.");
            ui.button(
                    dock,
                    "질문 작성",
                    true,
                    () -> {
                        navigate("question_edit", screen().withQuestion(""));
                    });
            ui.button(dock, "복습 화면으로", false, () -> navigate("review"));
            return;
        }
        ui.pair(
                content,
                mode == 0 ? "✓ 전체 원문" : "전체 원문",
                () -> {
                    mode = 0;
                    savedScroll = 0;
                    firstRender = true;
                    render();
                },
                mode == 1 ? "✓ 요약" : "요약",
                () -> {
                    mode = 1;
                    savedScroll = 0;
                    firstRender = true;
                    render();
                });
        ui.button(
                content,
                mode == 2 ? "✓ 교수 보충 설명" : "교수 보충 설명",
                false,
                () -> {
                    mode = 2;
                    savedScroll = 0;
                    firstRender = true;
                    render();
                });
        String value = mode == 1 ? summary : mode == 2 ? supplement : original;
        ui.heading(
                content,
                mode == 0 ? "전체 강의 원문" : mode == 1 ? "핵심 내용 요약" : "교수님 보충 설명",
                course.title);
        TextView fullText;
        if (value.trim().isEmpty()) {
            fullText =
                    ui.text(
                            content,
                            mode == 0
                                    ? "아직 작성된 전체 원문이 없어요."
                                    : "이 자료는 별도로 공개되지 않았어요. 전체 원문을 확인해 주세요.");
        } else {
            fullText = ui.text(content, value);
            fullText.setTextIsSelectable(true);
        }
        if (mode != 0) ui.note(content, "요약과 보충 설명은 전체 원문과 별도로 제공하는 자료예요.");
        if (mode == 0) {
            ui.button(
                    content,
                    "대주제 위치 찾기",
                    false,
                    () ->
                            ui.chooseTopic(
                                    course,
                                    screen().topicId,
                                    topic -> {
                                        jumpTopic = topic.title;
                                        render();
                                    }));
        }
        if (!teacher)
            ui.button(
                    dock,
                    download == null ? "전체 자료 기기에 저장" : "공개본 다시 저장",
                    true,
                    () ->
                            vm.repo.downloadMaterial(
                                    course.id,
                                    vm.userId(),
                                    callback(ignored -> {
                                        Toast.makeText(
                                                        requireContext(),
                                                        "공개 자료를 기기에 저장했어요",
                                                        Toast.LENGTH_SHORT)
                                                .show();
                                        render();
                                    })));
        else ui.button(dock, "자료 편집으로", true, () -> navigate("material_edit"));
        ui.button(dock, "복습 화면으로", false, () -> navigate("review"));
        final String text = value;
        final int restore = restoreScroll;
        firstRender = false;
        fullText.post(() -> {
            if (!isAdded() || getView() == null || !fullText.isAttachedToWindow()) return;
            NestedScrollView view = scroll();
            if (view == null || fullText.getLayout() == null) return;
            if (jumpTopic != null && mode == 0) {
                int index = text.indexOf(jumpTopic);
                jumpTopic = null;
                if (index >= 0) {
                    int line = fullText.getLayout().getLineForOffset(index);
                    view.smoothScrollTo(
                            0, fullText.getTop() + fullText.getLayout().getLineTop(line));
                } else
                    Toast.makeText(requireContext(), "원문에서 해당 대주제 제목을 찾지 못했어요", Toast.LENGTH_SHORT)
                            .show();
            } else view.scrollTo(0, restore);
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        out.putInt("mode", mode);
        NestedScrollView view = scroll();
        out.putInt("scroll", view == null ? savedScroll : view.getScrollY());
        super.onSaveInstanceState(out);
    }

    @Override
    public void onDestroyView() {
        NestedScrollView view = scroll();
        if (view != null) savedScroll = view.getScrollY();
        firstRender = true;
        super.onDestroyView();
    }
}
