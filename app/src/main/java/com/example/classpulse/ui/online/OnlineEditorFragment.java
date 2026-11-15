package com.example.classpulse.ui.online;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import androidx.annotation.NonNull;
import com.example.classpulse.domain.Models;
import com.example.classpulse.online.OnlineDatabase;
import com.example.classpulse.online.OnlineJson;
import com.example.classpulse.online.OnlineRepository;
import com.example.classpulse.ui.ScreenContext;
import com.example.classpulse.ui.TopicOutlineEditor;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class OnlineEditorFragment extends OnlineFragment {
    private String newId, titleText = "", description = "";
    private final List<Models.Topic> outline = new ArrayList<>();
    private EditText titleField, descriptionField;
    private boolean built, initialized, busy;
    private long baseRevision;

    @Override
    protected String title() {
        return screen.lessonId.isEmpty() ? "수업 준비" : "수업 정보 수정";
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        newId = state == null ? OnlineJson.id() : state.getString("new_id", OnlineJson.id());
        if (state == null) return;
        titleText = state.getString("title", "");
        description = state.getString("description", "");
        try {
            JSONArray saved = new JSONArray(state.getString("outline", "[]"));
            for (int i = 0; i < saved.length(); i++) readTopic(saved.getJSONObject(i));
        } catch (JSONException ignored) {
            outline.clear();
        }
        initialized = state.getBoolean("initialized") && !outline.isEmpty();
        baseRevision = state.getLong("base_revision");
    }

    private void readTopic(JSONObject value) {
        Models.Topic topic = new Models.Topic();
        topic.id = value.optString("id");
        topic.title = value.optString("title");
        topic.parentTopicId = value.isNull("parentTopicId") ? null : value.optString("parentTopicId");
        topic.position = outline.size();
        outline.add(topic);
    }

    private JSONArray writeOutline() {
        JSONArray payloadTopics = new JSONArray();
        for (Models.Topic topic : outline) {
            payloadTopics.put(OnlineJson.values(
                    "id", topic.id, "title", topic.title, "parentTopicId", topic.parentTopicId));
        }
        return payloadTopics;
    }

    @Override
    protected void changed() {
        if (!built) render();
    }

    private void capture() {
        if (titleField != null) titleText = titleField.getText().toString();
        if (descriptionField != null) description = descriptionField.getText().toString();
    }

    @Override
    protected void render() {
        if (shell == null || repo == null || built) return;
        boolean edit = !screen.lessonId.isEmpty();
        JSONObject session = lesson();
        shell.demoBanner.setVisibility(View.GONE);
        if (!initialized) {
            if (edit) {
                List<OnlineDatabase.Document> topics = topics();
                if (session.optString("id").isEmpty() || topics.size() != session.optInt("topicCount")) {
                    clear();
                    ui.note(shell.content, "목차를 불러오고 있어요.");
                    return;
                }
                if (!owner() || !"READY".equals(session.optString("status"))) {
                    clear();
                    ui.note(shell.content, "수업 시작 전에 담당 교수가 목차를 편집할 수 있어요.");
                    return;
                }
                titleText = session.optString("title");
                description = session.optString("description");
                baseRevision = session.optLong("outlineVersion");
                for (OnlineDatabase.Document topic : topics) readTopic(OnlineJson.object(topic.json));
            } else {
                readTopic(OnlineJson.values("id", OnlineJson.id(), "title", ""));
            }
            initialized = true;
        }
        clear();
        built = true;
        titleField = ui.field(shell.content, "수업명", titleText, false);
        descriptionField = ui.field(shell.content, "수업 설명 (선택)", description, true);
        descriptionField.setMinimumHeight(Math.round(72 * getResources().getDisplayMetrics().density));
        titleField.setEnabled(!busy);
        descriptionField.setEnabled(!busy);
        new TopicOutlineEditor(shell.content, outline, !busy);
        String saveLabel = edit ? "수업 정보 저장" : "수업 만들기";
        ui.button(shell.dock, busy ? "저장 중…" : saveLabel, true, this::save).setEnabled(!busy);
    }

    private void save() {
        if (busy) return;
        capture();
        if (titleText.trim().isEmpty() || outline.isEmpty()) {
            error("수업명과 대주제를 입력해 주세요.");
            return;
        }
        for (Models.Topic topic : outline) {
            if (topic.title.trim().isEmpty()) {
                boolean parent = topic.parentTopicId == null || topic.parentTopicId.isEmpty();
                error(parent ? "빈 대주제 제목을 확인해 주세요." : "빈 소주제 제목을 확인해 주세요.");
                return;
            }
        }
        boolean edit = !screen.lessonId.isEmpty();
        String sid = edit ? screen.lessonId : newId;
        JSONObject payload = OnlineJson.values(
                "sessionId", sid, "title", titleText, "description", description, "topics", writeOutline());
        if (edit) {
            try {
                payload.put("baseRevision", baseRevision);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        busy = true;
        built = false;
        render();
        final long token = viewToken();
        repo.command(edit ? "updateOutline" : "createSession", sid, payload,
                new OnlineRepository.Callback() {
                    public void success(JSONObject result) {
                        busy = false;
                        if (current(token)) {
                            host().replaceCurrent("online_session", ScreenContext.empty().forLesson(sid));
                        }
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
        out.putString("outline", writeOutline().toString());
        out.putBoolean("initialized", initialized);
        out.putLong("base_revision", baseRevision);
    }

    @Override
    public void onDestroyView() {
        capture();
        built = false;
        titleField = null;
        descriptionField = null;
        super.onDestroyView();
    }
}
