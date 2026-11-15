package com.example.classpulse.ui.common;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import com.example.classpulse.R;
import com.example.classpulse.data.LessonRepository;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class HomeFragment extends BaseFragment {
    private boolean deleting;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View v = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(v, "내 수업", false);
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        observe(this::render);
        vm.repo.initializationError().observe(getViewLifecycleOwner(), ignored -> render());
    }

    private void render() {
        clear();
        ui.heading(
                content,
                vm.teacher() ? "오늘의 수업을 준비해요" : "수업의 궁금증을 이어가요",
                vm.teacher() ? "대주제를 미리 정하고 학생들과 함께해요." : "참여한 수업과 내 기록을 다시 확인해요.");
        if (vm.teacher())
            ui.button(content, "새 수업 만들기", true, () -> navigate("create", screen().forLesson("")));
        else
            ui.pair(
                    content,
                    "초대 링크로 참여",
                    () -> {
                        vm.setPendingInvite("");
                        navigate("join");
                    },
                    "코드 직접 입력",
                    () -> {
                        vm.setPendingInvite("CODE");
                        navigate("join");
                    });
        String error = vm.repo.initializationError().getValue();
        if (error != null) {
            ui.note(content, error);
            ui.button(content, "자료 준비 다시 시도", false, () -> vm.repo.retryInitialization());
        }
        if (vm.lessons.getValue() == null) {
            if (error == null) ui.note(content, "이 기기의 수업 정보를 불러오고 있어요.");
            return;
        }
        int count = 0;
        for (Models.Lesson lesson : vm.currentLessons()) {
            if (vm.teacher()
                    ? !"demo-teacher".equals(lesson.teacherId)
                    : !lesson.isMember(vm.userId())) continue;
            count++;
            String status =
                    "LIVE".equals(lesson.status)
                            ? "수업 중"
                            : "ENDED".equals(lesson.status) ? "수업 종료" : "시작 전";
            String subtitle = lesson.description == null ? "" : lesson.description;
            if (Models.LIVE.equals(lesson.status) && lesson.currentVisitId != null
                    && lesson.currentTopic() != null)
                subtitle += "\n현재 주제 · " + lesson.topicPath(lesson.currentTopic());
            boolean canDelete = vm.teacher() && vm.userId().equals(lesson.teacherId)
                    && !Models.LIVE.equals(lesson.status) && !Models.isBuiltInLesson(lesson.id);
            ui.card(content, lesson.title, subtitle, status, () -> open(lesson),
                    canDelete ? anchor -> lessonMenu(lesson, anchor) : null);
        }
        if (count == 0)
            ui.card(content, "아직 참여한 수업이 없어요", "교수님이 공유한 초대 링크나 4자리 코드를 입력해 주세요.", "시작하기", null);
        ui.note(
                content,
                "시연 수업 참여 코드: 4821\n두 역할은 이 기기 안에서 같은 수업 데이터를 사용해요. 다른 기기와의 공유는 실제 서비스 연결 후 사용할 수 있어요.");
    }

    private void lessonMenu(Models.Lesson lesson, View anchor) {
        if (deleting) return;
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add("수업 삭제").setOnMenuItemClickListener(item -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("수업을 삭제할까요?")
                    .setMessage("‘" + lesson.title + "’의 수업과 이 기기에 저장된 관련 기록이 함께 삭제돼요.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제", (dialog, button) -> deleteLesson(lesson.id))
                    .show();
            return true;
        });
        menu.show();
    }

    private void deleteLesson(String id) {
        if (deleting || !isScreenActive() || !vm.teacher()) return;
        deleting = true;
        long generation = viewGeneration();
        vm.repo.deleteLesson(id, vm.userId(), new LessonRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void ignored) { deleting = false; }

            @Override
            public void onError(String message) {
                deleting = false;
                if (isCurrentView(generation)) showError(message);
            }
        });
    }

    private void open(Models.Lesson l) {
        navigate(
                "ENDED".equals(l.status)
                        ? "review"
                        : vm.teacher() && "READY".equals(l.status) ? "invite" : "live",
                screen().forLesson(l.id));
    }
}
