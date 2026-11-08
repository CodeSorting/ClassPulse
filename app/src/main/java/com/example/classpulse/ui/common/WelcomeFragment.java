package com.example.classpulse.ui.common;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.classpulse.R;
import com.example.classpulse.ui.BaseFragment;

public final class WelcomeFragment extends BaseFragment {
    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View v = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(v, "ClassPulse", false);
        ui.heading(content, "수업의 궁금증을\n이어가는 방법", "교수님과 학생의 소통을 연결해요.");
        // 앱의 핵심 기능 세 가지를 아이콘과 함께 소개한다.
        ui.feature(
                content,
                R.drawable.ic_cp_confused,
                "말하지 않아도 전하는 익명 반응",
                "이해가 안 돼요 · 너무 빨라요 등 네 가지 버튼으로 바로 알려요.");
        ui.feature(
                content,
                R.drawable.ic_cp_records,
                "대주제별로 남기는 질문",
                "수업 중에는 가볍게 표시하고, 시간이 날 때 질문을 완성해요.");
        ui.feature(
                content,
                R.drawable.ic_cp_done,
                "수업 후 원문과 답변으로 복습",
                "교수님이 공개한 답변과 강의 원문을 다시 확인해요.");
        ui.note(content, "Google 계정으로 온라인 수업에 참여하거나, 이 기기의 예시 수업으로 먼저 살펴볼 수 있어요.");
        ui.section(content, "기기 내 시연으로 살펴보기", null);
        ui.button(content, "교수자로 시작", true, () -> choose("TEACHER"));
        ui.button(content, "학생으로 시작", false, () -> choose("STUDENT"));
        ui.googleButton(dock, "Google로 로그인", () -> host().openGoogleAccount());
        return v;
    }

    private void choose(String role) {
        host().enterDemo(role);
    }
}
