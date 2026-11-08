package com.example.classpulse.ui.common;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.classpulse.R;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;

public final class ConfirmJoinFragment extends BaseFragment {
    private boolean joining;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View v = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(v, "참여할 수업 확인", true);
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        super.onViewCreated(v, b);
        observe(this::render);
    }

    private void render() {
        clear();
        Models.Lesson l = lesson();
        if (l == null) {
            ui.note(content, "수업 정보를 확인할 수 없어요. 링크나 코드를 다시 입력해 주세요.");
            ui.button(dock, "다시 입력", true, () -> host().back());
            return;
        }
        ui.heading(content, "이 수업이 맞나요?", null);
        ui.card(
                content,
                l.title,
                l.description,
                "LIVE".equals(l.status) ? "수업 중" : "ENDED".equals(l.status) ? "수업 종료" : "시작 전",
                null);
        ui.section(content, "오늘의 대주제", null);
        for (Models.Topic t : l.topics)
            ui.topic(
                    content,
                    (t.position + 1) + ". " + t.title,
                    t.completed ? "완료" : "예정",
                    false,
                    null);
        ui.note(content, "참여하면 내 수업 목록에 추가돼요. 질문과 반응은 학생의 실명 없이 표시돼요.");
        if (vm.teacher()) ui.note(content, "참여 버튼을 누르면 이 기기의 학생 시연 역할로 전환합니다.");
        ui.button(
                        dock,
                        joining
                                ? "참여 정보 저장 중…"
                                : l.isMember("demo-student") ? "수업으로 들어가기" : "이 수업에 참여하기",
                        true,
                        () -> join(l))
                .setEnabled(!joining);
    }

    private void join(Models.Lesson lesson) {
        if (joining || !isScreenActive()) return;
        joining = true;
        final long generation = viewGeneration();
        render();
        vm.repo.joinById(
                "demo-student",
                lesson.id,
                new com.example.classpulse.data.LessonRepository.Callback<String>() {
                    @Override
                    public void onSuccess(String id) {
                        joining = false;
                        if (!isCurrentView(generation)) return;
                        if (vm.teacher()) vm.setRole("STUDENT");
                        vm.setPendingInvite("");
                        Models.Lesson latest = vm.lesson(id);
                        host().openJoinedLesson(
                                        "ENDED"
                                                        .equals(
                                                                latest == null
                                                                        ? lesson.status
                                                                        : latest.status)
                                                ? "review"
                                                : "live",
                                        screen().forLesson(id));
                    }

                    @Override
                    public void onError(String message) {
                        joining = false;
                        if (isCurrentView(generation)) {
                            showError(message);
                            render();
                        }
                    }
                });
    }
}
