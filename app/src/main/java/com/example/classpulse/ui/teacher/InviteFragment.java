package com.example.classpulse.ui.teacher;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.classpulse.R;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class InviteFragment extends BaseFragment {
    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "학생 초대", true);
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
        if (!vm.teacher() || !vm.userId().equals(lesson.teacherId)) {
            ui.note(content, "이 수업의 교수자만 초대와 수업 시작을 관리할 수 있어요.");
            return;
        }
        ui.heading(content, lesson.title, lesson.description);
        ui.code(content, lesson.code, "학생 참여 코드", "학생은 이 4자리 코드를 입력해 수업을 확인해요.");
        ui.button(content, "코드 복사", false, () -> copy(lesson.code));
        String link = "classpulse://join/" + lesson.id;
        ui.card(content, "수업 초대 링크", link, "로컬 시연", null);
        ui.pair(
                content,
                "링크 복사",
                () -> copy(link),
                "링크 공유",
                () -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(
                            Intent.EXTRA_TEXT,
                            lesson.title
                                    + "\n"
                                    + link
                                    + "\n참여 코드: "
                                    + lesson.code
                                    + "\n현재는 같은 기기의 로컬 시연용 초대입니다.");
                    startActivity(Intent.createChooser(send, "수업 초대 공유"));
                });
        ui.note(content, "초대 링크 또는 코드로 수업을 확인한 뒤 참여해요. 다른 기기와 연결되는 초대는 Firebase 연동 후 사용할 수 있어요.");
        if ("READY".equals(lesson.status))
            ui.button(content, "수업·대주제 수정", false, () -> navigate("edit_lesson"));
        ui.stats(
                content,
                "참여 학생",
                Integer.toString(lesson.members.size()),
                "대주제",
                Integer.toString(lesson.topics.size()));
        if ("READY".equals(lesson.status))
            ui.button(
                    dock,
                    "첫 대주제로 수업 시작",
                    true,
                    () ->
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("수업을 시작할까요?")
                                    .setMessage("첫 대주제부터 진행합니다. 이번 버전은 강의 녹음을 시작하지 않아요.")
                                    .setNegativeButton("취소", null)
                                    .setPositiveButton(
                                            "수업 시작",
                                            (d, w) ->
                                                    vm.repo.startLesson(
                                                            lesson.id,
                                                            callback(v -> navigate("live"))))
                                    .show());
        else
            ui.button(
                    dock,
                    "수업으로 이동",
                    true,
                    () -> navigate("ENDED".equals(lesson.status) ? "review" : "live"));
    }

    private void copy(String value) {
        ClipboardManager clipboard =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null)
            clipboard.setPrimaryClip(ClipData.newPlainText("ClassPulse 초대", value));
        Toast.makeText(requireContext(), "복사했어요.", Toast.LENGTH_SHORT).show();
    }
}
