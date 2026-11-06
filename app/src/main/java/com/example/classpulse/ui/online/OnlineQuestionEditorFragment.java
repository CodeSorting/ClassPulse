package com.example.classpulse.ui.online;

import android.os.*;
import android.text.*;
import android.widget.*;
import androidx.annotation.NonNull;
import com.example.classpulse.online.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONObject;
import java.util.List;

public final class OnlineQuestionEditorFragment extends OnlineFragment {
    private EditText input;
    private TextView topicLabel, status;
    private String value = "", topic = "";
    private boolean built, typed, sealed, setting;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable save = () -> flush(false);

    @Override
    protected String title() {
        return "대주제에 질문 남기기";
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            value = saved.getString("value", "");
            topic = saved.getString("topic", "");
            typed = saved.getBoolean("typed");
            sealed = saved.getBoolean("sealed");
        }
    }

    @Override
    protected void changed() {
        if (!built) {
            render();
            return;
        }
        OnlineDatabase.Draft d = draft("QUESTION_DRAFT", screen.questionId);
        if (d != null && !typed) {
            setting = true;
            value = d.text;
            topic = d.topicId;
            input.setText(value);
            setting = false;
            sealed = d.closed;
        }
        if (status != null)
            status.setText(
                    d == null
                            ? "이 기기에 자동 저장돼요"
                            : d.closed
                                    ? "제출 준비됨 · 서버 접수 상태는 수업 화면에서 확인해요"
                                    : d.dirty ? "기기에 저장됨 · 동기화 대기" : "개인 초안 저장됨");
        if (topicLabel != null) topicLabel.setText(topicTitle(topic));
    }

    @Override
    protected void render() {
        if (shell == null || repo == null || built) return;
        if (!draftsLoaded || lesson().optString("id").isEmpty() || topics().isEmpty()) {
            clear();
            ui.note(shell.content, "질문할 대주제를 불러오고 있어요.");
            showNotice();
            return;
        }
        if (topic.isEmpty())
            topic =
                    screen.topicId.isEmpty()
                            ? lesson().optJSONObject("progress")
                                    .optString("currentTopicId", topics().get(0).id)
                            : screen.topicId;
        if (topic.isEmpty() || "null".equals(topic)) topic = topics().get(0).id;
        OnlineDatabase.Draft d = draft("QUESTION_DRAFT", screen.questionId);
        if (d != null && !typed) {
            value = d.text;
            topic = d.topicId;
            sealed = d.closed;
        }
        clear();
        built = true;
        ui.heading(shell.content, "궁금한 내용을 편하게 남겨요", "초안은 나만 볼 수 있어요. 제출하면 담당 교수에게 전달돼요.");
        topicLabel = ui.text(shell.content, topicTitle(topic));
        ui.button(
                        shell.content,
                        "질문할 대주제 변경",
                        false,
                        () -> {
                            List<OnlineDatabase.Document> all = topics();
                            String[] names = new String[all.size()];
                            for (int i = 0; i < all.size(); i++)
                                names[i] = OnlineJson.object(all.get(i).json).optString("title");
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("질문할 대주제")
                                    .setItems(
                                            names,
                                            (dialog, which) -> {
                                                topic = all.get(which).id;
                                                typed = true;
                                                topicLabel.setText(topicTitle(topic));
                                                handler.removeCallbacks(save);
                                                handler.postDelayed(save, 900);
                                            })
                                    .show();
                        })
                .setEnabled(!sealed);
        input = ui.field(shell.content, "질문 내용 · 1,000자 이내", value, true);
        input.setEnabled(!sealed);
        input.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

                    public void onTextChanged(CharSequence s, int st, int before, int count) {
                        if (setting) return;
                        typed = true;
                        value = s.toString();
                        handler.removeCallbacks(save);
                        handler.postDelayed(save, 900);
                    }

                    public void afterTextChanged(Editable e) {}
                });
        status =
                ui.note(
                        shell.content,
                        d != null && d.dirty ? "기기에 저장됨 · 동기화 대기" : "개인 초안은 다른 학생과 교수에게 보이지 않아요.");
        ui.button(
                        shell.dock,
                        sealed ? "제출 준비됨" : "질문 제출",
                        true,
                        () -> {
                            if (sealed) return;
                            if (input.getText().toString().trim().isEmpty()) {
                                error("질문 내용을 입력해 주세요.");
                                return;
                            }
                            handler.removeCallbacks(save);
                            value = input.getText().toString();
                            repo.saveDraft(
                                    screen.lessonId,
                                    screen.questionId,
                                    topic,
                                    value,
                                    true,
                                    callback(v -> {
                                        sealed = true;
                                        host().replaceCurrent(
                                                        "online_session",
                                                        screen.forLesson(screen.lessonId));
                                    }));
                        })
                .setEnabled(!sealed);
        ui.button(shell.dock, "개인 초안 저장", false, () -> flush(false)).setEnabled(!sealed);
        if (sealed)
            ui.button(
                    shell.content,
                    "원고를 새 질문으로 복사",
                    false,
                    () -> {
                        String copy = OnlineJson.id();
                        repo.saveDraft(
                                screen.lessonId,
                                copy,
                                topic,
                                input.getText().toString(),
                                false,
                                callback(v ->
                                        open(
                                                "online_question_edit",
                                                screen.withQuestion(copy).withTopic(topic))));
                    });
    }

    private void flush(boolean unused) {
        if (repo == null || input == null || sealed || topic.isEmpty()) return;
        value = input.getText().toString();
        repo.saveDraft(screen.lessonId, screen.questionId, topic, value, false, null);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(save);
        flush(false);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("value", input == null ? value : input.getText().toString());
        out.putString("topic", topic);
        out.putBoolean("typed", typed);
        out.putBoolean("sealed", sealed);
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(save);
        input = null;
        topicLabel = null;
        status = null;
        built = false;
        super.onDestroyView();
    }
}
