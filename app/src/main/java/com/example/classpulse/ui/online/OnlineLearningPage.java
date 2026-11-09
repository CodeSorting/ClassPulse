package com.example.classpulse.ui.online;

import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.appcompat.app.AlertDialog;
import com.example.classpulse.online.OnlineDatabase;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

abstract class OnlineLearningPage extends OnlineFragment {
    protected boolean loading, sending;
    protected JSONObject content;
    private long loadGeneration;
    private String lessonViewKey;

    protected abstract String loadAction();

    @Override
    protected void changed() {
        if (shell == null || repo == null) return;
        JSONObject lesson = lesson();
        StringBuilder key = new StringBuilder()
                .append(lesson.optString("id")).append('\n')
                .append(lesson.optString("status")).append('\n')
                .append(lesson.optString("title")).append('\n')
                .append(lesson.optString("ownerUid"));
        for (OnlineDatabase.Document topic : topics()) key.append('\n').append(topic.id).append(topic.json);
        String next = key.toString();
        if (next.equals(lessonViewKey)) return;
        lessonViewKey = next;
        render();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (shell != null && repo != null) reload();
    }

    @Override
    public void onStop() {
        loadGeneration++;
        loading = false;
        sending = false;
        super.onStop();
    }

    protected void reload() {
        reload(false);
    }

    protected void reload(boolean quiet) {
        if (loading) return;
        long generation = ++loadGeneration;
        loading = true;
        learning(loadAction(), value -> {
            if (generation != loadGeneration) return;
            loading = false;
            content = value;
            render();
        }, message -> {
            if (generation != loadGeneration) return;
            loading = false;
            render();
            if (!quiet) error(message);
        });
    }

    protected void request(String action, Consumer<JSONObject> success, Object... fields) {
        if (sending) return;
        sending = true;
        render();
        learning(action, value -> {
            sending = false;
            success.accept(value);
        }, message -> {
            sending = false;
            render();
            error(message);
        }, fields);
    }

    protected void chooseTopic(boolean includeUnassigned, Consumer<String> selected) {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (includeUnassigned) {
            ids.add("");
            names.add("대주제 미지정");
        }
        topics().forEach(topic -> {
            ids.add(topic.id);
            names.add(topicTitle(topic.id));
        });
        if (ids.isEmpty()) {
            error("대주제를 불러오고 있어요.");
            return;
        }
        new MaterialAlertDialogBuilder(requireContext()).setTitle("대주제 선택")
                .setItems(names.toArray(new String[0]), (dialog, index) -> selected.accept(ids.get(index)))
                .setNegativeButton("취소", null)
                .show();
    }

    protected LinearLayout editorContent() {
        LinearLayout fields = new LinearLayout(requireContext());
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, padding / 2, padding, padding / 2);
        return fields;
    }

    protected MaterialAlertDialogBuilder editor(String title, LinearLayout fields) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(fields);
        return new MaterialAlertDialogBuilder(requireContext()).setTitle(title).setView(scroll)
                .setNegativeButton("닫기", null);
    }

    protected void saveEditor(AlertDialog dialog, String action, Runnable success, Object... fields) {
        if (sending) return;
        sending = true;
        learning(action, value -> {
            sending = false;
            dialog.dismiss();
            success.run();
        }, message -> {
            sending = false;
            error(message);
        }, fields);
    }

    protected static JSONObject find(JSONArray items, String key, String value) {
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && value.equals(item.optString(key))) return item;
        }
        return new JSONObject();
    }
}
