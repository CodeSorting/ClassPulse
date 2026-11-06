package com.example.classpulse.ui.online;

import com.example.classpulse.online.*;
import com.example.classpulse.ui.ScreenContext;

public final class OnlineHomeFragment extends OnlineFragment {
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
        for (OnlineDatabase.Session s : sessions)
            ui.card(
                    shell.content,
                    s.title,
                    (repo.uid.equals(s.ownerUid) ? "내가 운영하는 수업" : "참여 중인 수업")
                            + " · "
                            + stateLabel(s.status),
                    "온라인",
                    () -> open("online_session", ScreenContext.empty().forLesson(s.id)));
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
