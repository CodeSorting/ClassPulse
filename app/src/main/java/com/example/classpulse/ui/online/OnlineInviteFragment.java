package com.example.classpulse.ui.online;

import android.content.*;
import com.example.classpulse.online.*;
import org.json.JSONObject;

public final class OnlineInviteFragment extends OnlineFragment {
    private JSONObject invite = new JSONObject();
    private boolean requested, busy;

    @Override
    protected String title() {
        return "코드와 링크 초대";
    }

    @Override
    protected void render() {
        if (shell == null || repo == null) return;
        clear();
        if (!requested && owner()) {
            requested = true;
            load();
        }
        ui.heading(
                shell.content,
                lesson().optString("title", "수업 확인 중"),
                "학생은 링크 또는 코드로 수업을 확인한 뒤 참여해요.");
        showNotice();
        String code = invite.optString("code"), link = invite.optString("link");
        if (code.isEmpty()) ui.note(shell.content, "초대를 발급하면 코드와 링크를 함께 공유할 수 있어요.");
        else {
            ui.code(shell.content, code, "참여 코드", "발급 후 24시간 유효 · 새로 발급하면 이전 초대가 만료돼요.");
            ui.note(shell.content, link);
            ui.pair(shell.content, "코드 복사", () -> copy(code), "링크 복사", () -> copy(link));
            ui.button(
                    shell.content,
                    "초대 공유",
                    true,
                    () -> {
                        Intent i =
                                new Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(
                                                Intent.EXTRA_TEXT,
                                                lesson().optString("title")
                                                        + "\nClassPulse 초대\n"
                                                        + link
                                                        + "\n참여 코드: "
                                                        + code);
                        startActivity(Intent.createChooser(i, "수업 초대 공유"));
                    });
            ui.note(
                    shell.content,
                    "현재 링크는 ClassPulse가 설치된 기기의 앱 링크예요. 링크를 참여 화면에 붙여넣어 확인할 수도 있어요.");
        }
        if (owner())
            ui.button(
                            shell.dock,
                            busy ? "발급 중…" : code.isEmpty() ? "코드·링크 발급" : "초대 새로 발급",
                            false,
                            () -> {
                                if (busy) return;
                                busy = true;
                                render();
                                final long token = viewToken();
                                repo.command(
                                        "issueInvite",
                                        screen.lessonId,
                                        OnlineJson.values("sessionId", screen.lessonId),
                                        new OnlineRepository.Callback() {
                                            public void success(JSONObject value) {
                                                busy = false;
                                                if (current(token)) load();
                                            }

                                            public void error(String r, String m) {
                                                busy = false;
                                                if (current(token)) {
                                                    render();
                                                    OnlineInviteFragment.this.error(m);
                                                }
                                            }
                                        });
                            })
                    .setEnabled(!busy);
    }

    private void load() {
        repo.read(
                "getInvite",
                OnlineJson.values("sessionId", screen.lessonId),
                callback(v -> {
                    invite = v;
                    render();
                }));
    }

    private void copy(String text) {
        ((ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("ClassPulse 초대", text));
    }

    @Override
    public void onDestroyView() {
        requested = false;
        super.onDestroyView();
    }
}
