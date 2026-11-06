package com.example.classpulse.ui.online;

import android.os.Bundle;
import android.widget.EditText;
import androidx.annotation.NonNull;
import com.example.classpulse.online.*;
import com.example.classpulse.ui.ScreenContext;
import org.json.*;
import java.util.*;

public final class OnlineEditorFragment extends OnlineFragment {
    private String newId, titleText = "", description = "";
    private JSONArray outline = new JSONArray();
    private final List<EditText> fields = new ArrayList<>();
    private EditText titleField, descriptionField;
    private boolean built, initialized, busy;
    private long baseRevision;

    @Override
    protected String title() {
        return "수업과 대주제 준비";
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        newId = state == null ? OnlineJson.id() : state.getString("new_id", OnlineJson.id());
        if (state != null) {
            titleText = state.getString("title", "");
            description = state.getString("description", "");
            try {
                outline = new JSONArray(state.getString("outline", "[]"));
            } catch (Exception ignored) {
            }
            initialized = state.getBoolean("initialized");
            baseRevision = state.getLong("base_revision");
        }
    }

    @Override
    protected void changed() {
        if (!built) render();
    }

    private void capture() {
        if (titleField != null) titleText = titleField.getText().toString();
        if (descriptionField != null) description = descriptionField.getText().toString();
        for (int i = 0; i < fields.size() && i < outline.length(); i++)
            try {
                outline.getJSONObject(i).put("title", fields.get(i).getText().toString());
            } catch (Exception ignored) {
            }
    }

    @Override
    protected void render() {
        if (shell == null || repo == null || built) return;
        boolean edit = !screen.lessonId.isEmpty();
        JSONObject s = lesson();
        if (!initialized) {
            if (edit
                    && (s.optString("id").isEmpty() || topics().size() != s.optInt("topicCount"))) {
                clear();
                ui.note(shell.content, "목차를 불러오고 있어요.");
                return;
            }
            if (edit) {
                titleText = s.optString("title");
                description = s.optString("description");
                baseRevision = s.optLong("outlineVersion");
                for (OnlineDatabase.Document t : topics()) outline.put(OnlineJson.object(t.json));
            } else outline.put(OnlineJson.values("id", OnlineJson.id(), "title", ""));
            initialized = true;
        }
        clear();
        fields.clear();
        built = true;
        ui.heading(shell.content, "대주제를 먼저 준비해요", "제목과 순서를 정하고 수업에서 완료 버튼으로 진행해요.");
        titleField = ui.field(shell.content, "수업명", titleText, false);
        descriptionField = ui.field(shell.content, "수업 설명", description, true);
        for (int i = 0; i < outline.length(); i++) {
            final int pos = i;
            JSONObject t = outline.optJSONObject(i);
            fields.add(ui.field(shell.content, (i + 1) + ". 대주제", t.optString("title"), false));
            ui.pair(shell.content, "위로", () -> move(pos, -1), "아래로", () -> move(pos, 1));
            ui.button(
                    shell.content,
                    "대주제 " + (i + 1) + " 삭제",
                    false,
                    () -> {
                        capture();
                        outline.remove(pos);
                        built = false;
                        render();
                    });
        }
        ui.button(
                shell.content,
                "대주제 추가",
                false,
                () -> {
                    capture();
                    outline.put(OnlineJson.values("id", OnlineJson.id(), "title", ""));
                    built = false;
                    render();
                });
        ui.button(shell.dock, busy ? "서버 확인 중…" : edit ? "수업 정보 저장" : "수업 만들기", true, this::save)
                .setEnabled(!busy);
    }

    private void move(int i, int delta) {
        capture();
        int j = i + delta;
        if (j < 0 || j >= outline.length()) return;
        try {
            Object a = outline.get(i);
            outline.put(i, outline.get(j));
            outline.put(j, a);
        } catch (Exception ignored) {
        }
        built = false;
        render();
    }

    private void save() {
        if (busy) return;
        capture();
        if (titleText.trim().isEmpty() || outline.length() == 0) {
            error("수업명과 대주제를 입력해 주세요.");
            return;
        }
        for (int i = 0; i < outline.length(); i++)
            if (outline.optJSONObject(i).optString("title").trim().isEmpty()) {
                error("빈 대주제 제목을 확인해 주세요.");
                return;
            }
        boolean edit = !screen.lessonId.isEmpty();
        String sid = edit ? screen.lessonId : newId;
        JSONArray payloadTopics = new JSONArray();
        for (int i = 0; i < outline.length(); i++) {
            JSONObject t = outline.optJSONObject(i);
            payloadTopics.put(
                    OnlineJson.values("id", t.optString("id"), "title", t.optString("title")));
        }
        JSONObject payload =
                OnlineJson.values(
                        "sessionId",
                        sid,
                        "title",
                        titleText,
                        "description",
                        description,
                        "topics",
                        payloadTopics);
        if (edit)
            try {
                payload.put("baseRevision", baseRevision);
            } catch (Exception ignored) {
            }
        busy = true;
        built = false;
        render();
        final long token = viewToken();
        repo.command(
                edit ? "updateOutline" : "createSession",
                sid,
                payload,
                new OnlineRepository.Callback() {
                    public void success(JSONObject result) {
                        busy = false;
                        if (current(token))
                            host().replaceCurrent(
                                            "online_session", ScreenContext.empty().forLesson(sid));
                    }

                    public void error(String reason, String message) {
                        busy = false;
                        if (current(token)) {
                            built = false;
                            render();
                            OnlineEditorFragment.this.error(message);
                        }
                    }
                });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        capture();
        super.onSaveInstanceState(out);
        out.putString("new_id", newId);
        out.putString("title", titleText);
        out.putString("description", description);
        out.putString("outline", outline.toString());
        out.putBoolean("initialized", initialized);
        out.putLong("base_revision", baseRevision);
    }

    @Override
    public void onDestroyView() {
        capture();
        built = false;
        titleField = null;
        descriptionField = null;
        fields.clear();
        super.onDestroyView();
    }
}
