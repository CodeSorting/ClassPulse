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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class SettingsFragment extends BaseFragment {
    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View v = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(v, "내 정보", true);
        return v;
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle b) {
        super.onViewCreated(v, b);
        observe(this::render);
    }

    private void render() {
        clear();
        ui.heading(
                content, vm.teacher() ? "교수자 시연 계정" : "학생 시연 계정", "이 화면의 수업과 기록은 기기 내 시연 데이터예요.");
        ui.button(content, "내 Google 계정", false, () -> host().openGoogleAccount());
        String key = vm.teacher() ? "teacher_name" : "student_name";
        android.content.SharedPreferences prefs =
                requireContext().getSharedPreferences("local_demo", 0);
        ui.card(
                content,
                prefs.getString(key, vm.teacher() ? "교수자" : "학생"),
                "이 기기에만 저장된 표시 이름",
                "로컬 프로필",
                () ->
                        ui.prompt(
                                "표시 이름",
                                prefs.getString(key, ""),
                                false,
                                name -> {
                                    if (name.isEmpty()) {
                                        showError("표시 이름을 입력해 주세요.");
                                        return;
                                    }
                                    prefs.edit().putString(key, name).apply();
                                    render();
                                }));
        int downloads = 0, drafts = 0;
        for (Models.Lesson l : vm.currentLessons()) {
            for (Models.Download d : l.downloads) if (vm.userId().equals(d.studentId)) downloads++;
            for (Models.Question q : l.questions)
                if (vm.userId().equals(q.studentId) && "DRAFT".equals(q.status)) drafts++;
        }
        ui.stats(
                content, "작성 중 질문", String.valueOf(drafts), "저장한 수업 자료", String.valueOf(downloads));
        ui.note(content, "질문·개인 표시·복습 기록은 앱을 다시 실행해도 유지돼요. 온라인 공유·계정 복원은 실제 서비스 연결 후 사용할 수 있어요.");
        ui.button(
                content,
                "다운로드 자료만 정리",
                false,
                () ->
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle("기기 저장 공간 정리")
                                .setMessage(
                                        "이 계정으로 내려받은 복습 자료만 지웁니다. 교수의 공개 원본, 질문 초안, 개인 표시와 응답 기록은 남습니다.")
                                .setNegativeButton("취소", null)
                                .setPositiveButton(
                                        "다운로드만 지우기",
                                        (d, w) ->
                                                vm.repo.clearDownloads(
                                                        vm.userId(),
                                                        callback(v -> {
                                                            render();
                                                        })))
                                .show());
        ui.heading(content, "역할을 바꿔 확인해요", null);
        ui.button(
                content,
                vm.teacher() ? "학생 시연으로 전환" : "교수자 시연으로 전환",
                true,
                () -> {
                    vm.setRole(vm.teacher() ? "STUDENT" : "TEACHER");
                    host().navigateRoot("home");
                });
        ui.button(
                dock,
                "시연 종료",
                false,
                () ->
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle("시연을 종료할까요?")
                                .setMessage("현재 역할을 해제합니다. 이 기기에 저장한 질문과 수업 자료는 유지됩니다.")
                                .setNegativeButton("계속 사용", null)
                                .setPositiveButton(
                                        "종료",
                                        (d, w) -> {
                                            vm.setRole("NONE");
                                            host().navigateRoot("welcome");
                                        })
                                .show());
    }
}
