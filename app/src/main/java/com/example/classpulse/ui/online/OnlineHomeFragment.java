package com.example.classpulse.ui.online;

import android.view.View;
import android.widget.Toast;
import androidx.appcompat.widget.PopupMenu;
import com.example.classpulse.online.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import com.example.classpulse.ui.ScreenContext;

public final class OnlineHomeFragment extends OnlineFragment {
    private final Set<String> deleting = new HashSet<>();
    @Override
    protected String title() {
        return "내 온라인 수업";
    }

    @Override
    protected String navigationTab() {
        return "online_home";
    }

    @Override
    protected boolean observesHome() {
        return true;
    }

    @Override
    protected void render() {
        if (shell == null || repo == null) return;
        clear();
        ui.heading(shell.content, "수업을 함께 이어가요", "Google 계정으로 참여한 수업이에요.");
        showNotice();
        ui.button(
                shell.content, "새 수업 만들기", true, () -> open("online_edit", ScreenContext.empty()));
        ui.button(
                shell.content,
                "코드·링크로 참여",
                false,
                () -> open("online_join", ScreenContext.empty()));
        if (sessions.isEmpty()) ui.note(shell.content, "아직 불러온 수업이 없어요. 수업을 만들거나 초대를 확인해 주세요.");
        // 수업 상태별로 묶어서 보여 준다: 지금 진행 중 → 시작 전 → 종료(복습).
        group("LIVE", "지금 진행 중인 수업", "눌러서 바로 수업에 참여해요.");
        group("READY", "시작 전 수업", "목차와 초대를 준비하고 수업 시작을 기다려요.");
        group("ENDED", "종료된 수업 · 복습", "원문·요약·공개 Q&A·퀴즈로 다시 확인해요.");
        ui.button(
                shell.dock,
                "서버 상태 다시 확인",
                false,
                () -> {
                    repo.retry();
                    if (subscriptionRefresh != null) subscriptionRefresh.close();
                    subscriptionRefresh = repo.watchHome();
                });
    }

    private void group(String status, String title, String subtitle) {
        int count = 0;
        for (OnlineDatabase.Session s : sessions) if (inGroup(status, s.status)) count++;
        if (count == 0) return;
        ui.section(shell.content, title + " " + count, subtitle);
        for (OnlineDatabase.Session s : sessions) {
            if (!inGroup(status, s.status)) continue;
            boolean owned = repo.uid.equals(s.ownerUid);
            ui.card(
                    shell.content,
                    s.title,
                    (owned ? "내가 운영하는 수업" : "참여 중인 수업") + " · " + nextStep(status, owned),
                    deleting.contains(s.id) ? "삭제 중…" : stateLabel(s.status),
                    () -> open("online_session", ScreenContext.empty().forLesson(s.id)),
                    owned && !"LIVE".equals(s.status) && !deleting.contains(s.id) ? anchor -> showLessonMenu(anchor, s) : null);
        }
    }

    /** LIVE·ENDED가 아닌 상태는 모두 "시작 전"으로 묶어 목록에서 빠지지 않게 한다. */
    private static boolean inGroup(String group, String status) {
        if ("READY".equals(group)) return !"LIVE".equals(status) && !"ENDED".equals(status);
        return group.equals(status);
    }

    /** 카드를 눌렀을 때 무엇을 할 수 있는지 한 줄로 알려 준다. */
    private static String nextStep(String status, boolean owned) {
        switch (status) {
            case "LIVE":
                return owned ? "진도 관리·반응 확인" : "반응·질문 보내기";
            case "ENDED":
                return owned ? "녹음 변환·자료 공개" : "원문·요약·퀴즈 복습";
            default:
                return owned ? "목차 편집·초대 후 시작" : "교수님의 시작을 기다리는 중";
        }
    }

    private void showLessonMenu(View anchor, OnlineDatabase.Session session) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add("수업 삭제");
        menu.setOnMenuItemClickListener(item -> {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("수업을 삭제할까요?")
                    .setMessage("‘" + session.title + "’이 목록에서 사라지고, 참여 학생도 수업과 복습 자료를 열 수 없어요.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("삭제", (dialog, which) -> deleteLesson(session.id))
                    .show();
            return true;
        });
        menu.show();
    }

    private void deleteLesson(String id) {
        if (!deleting.add(id)) return;
        long token = viewToken();
        render();
        repo.command("deleteSession", id, OnlineJson.values("sessionId", id), new OnlineRepository.Callback() {
            @Override
            public void success(JSONObject value) {
                deleting.remove(id);
                if (!current(token)) return;
                render();
                Toast.makeText(requireContext(), "수업을 삭제했어요.", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void error(String reason, String message) {
                deleting.remove(id);
                if (!current(token)) return;
                render();
                OnlineHomeFragment.this.error(message);
            }
        });
    }

    private OnlineTransport.Subscription subscriptionRefresh;

    @Override
    public void onStop() {
        if (subscriptionRefresh != null) {
            subscriptionRefresh.close();
            subscriptionRefresh = null;
        }
        super.onStop();
    }
}
