package com.example.classpulse.ui.online;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import androidx.annotation.NonNull;
import com.example.classpulse.online.*;
import org.json.JSONObject;

public final class OnlineJoinFragment extends OnlineFragment {
    private EditText code, link;
    private JSONObject preview;
    private String codeText = "", linkText = "";
    private boolean built, busy;

    @Override
    protected String title() {
        return "온라인 수업 참여";
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            codeText = state.getString("code", "");
            linkText = state.getString("link", "");
            preview = OnlineJson.object(state.getString("preview", "{}"));
        }
    }

    @Override
    protected void changed() {
        if (!built) render();
    }

    @Override
    protected void render() {
        if (shell == null || repo == null || built) return;
        clear();
        built = true;
        if (linkText.isEmpty()) {
            String pending = host().pendingInvite();
            if (!OnlineInvites.token(pending).isEmpty()) linkText = pending;
        }
        ui.heading(shell.content, "초대받은 수업을 확인해요", "링크 또는 코드 중 하나만 있으면 돼요. 확인 후 참여를 눌러 주세요.");
        code = ui.field(shell.content, "참여 코드 4자리", codeText, false);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        ui.button(
                shell.content,
                "코드로 수업 확인",
                true,
                () -> resolve("CODE", code.getText().toString().trim()));
        link = ui.field(shell.content, "초대 링크", linkText, false);
        ui.button(
                shell.content,
                "링크로 수업 확인",
                false,
                () -> {
                    String t = OnlineInvites.token(link.getText().toString());
                    if (t.isEmpty()) {
                        error("ClassPulse 초대 링크를 붙여넣어 주세요.");
                        return;
                    }
                    resolve("LINK", t);
                });
        if (preview != null && !preview.optString("previewToken").isEmpty()) previewCard();
    }

    private void resolve(String kind, String value) {
        if (busy) return;
        busy = true;
        final long token = viewToken();
        repo.read(
                "resolveInvite",
                OnlineJson.values("kind", kind, "value", value),
                new OnlineRepository.Callback() {
                    private boolean valid() {
                        return current(token);
                    }

                    public void success(JSONObject p) {
                        if (!valid()) return;
                        busy = false;
                        preview = p;
                        codeText = code.getText().toString();
                        linkText = link.getText().toString();
                        built = false;
                        render();
                    }

                    public void error(String r, String m) {
                        if (valid()) {
                            busy = false;
                            OnlineJoinFragment.this.error(m);
                        }
                    }
                });
    }

    private void previewCard() {
        ui.card(
                shell.content,
                preview.optString("title"),
                preview.optString("teacherDisplayName")
                        + " · "
                        + stateLabel(preview.optString("status")),
                "참여 전 확인",
                null);
        ui.button(
                shell.dock,
                "확인한 수업에 참여",
                true,
                () -> {
                    if (busy) return;
                    busy = true;
                    final long token = viewToken();
                    repo.command(
                            "joinSession",
                            preview.optString("sessionId"),
                            OnlineJson.values("previewToken", preview.optString("previewToken")),
                            new OnlineRepository.Callback() {
                                public void success(JSONObject v) {
                                    busy = false;
                                    if (current(token)) {
                                        host().clearPendingInvite();
                                        host().openOnlineJoined(v.optString("sessionId"));
                                    }
                                }

                                public void error(String r, String m) {
                                    busy = false;
                                    if (current(token)) OnlineJoinFragment.this.error(m);
                                }
                            });
                });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("code", code == null ? codeText : code.getText().toString());
        out.putString("link", link == null ? linkText : link.getText().toString());
        out.putString("preview", preview == null ? "{}" : preview.toString());
    }

    @Override
    public void onDestroyView() {
        if (code != null) codeText = code.getText().toString();
        if (link != null) linkText = link.getText().toString();
        built = false;
        busy = false;
        code = null;
        link = null;
        super.onDestroyView();
    }
}
