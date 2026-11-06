package com.example.classpulse.ui.online;

import android.os.*;
import android.text.*;
import android.widget.*;
import androidx.annotation.NonNull;
import com.example.classpulse.online.*;
import org.json.JSONObject;

public final class OnlineQuestionDetailFragment extends OnlineFragment {
    private EditText input;
    private TextView publicAnswer, status;
    private String text = "";
    private boolean built, typed, setting, busy;
    private long baseRevision, baseAnswerVersion;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveLocal = this::flush;

    @Override
    protected String title() {
        return "질문과 답변";
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            text = state.getString("text", "");
            typed = state.getBoolean("typed");
            baseRevision = state.getLong("base_revision");
            baseAnswerVersion = state.getLong("answer_version");
        }
    }

    @Override
    protected void changed() {
        if (built && lesson().optString("id").isEmpty()) {
            built = false;
            clear();
            input = null;
            publicAnswer = null;
            status = null;
            ui.note(shell.content, "수업 접근 권한을 확인해 주세요.");
            return;
        }
        if (!built) {
            render();
            return;
        }
        JSONObject q = item("QUESTION", screen.questionId);
        if (publicAnswer != null)
            publicAnswer.setText(
                    q.optString("answer").isEmpty() ? "아직 공개된 답변이 없어요." : q.optString("answer"));
        OnlineDatabase.Draft d = draft("ANSWER_DRAFT", screen.questionId);
        JSONObject remote = item("ANSWER_DRAFT", "answer_" + screen.questionId);
        if (input != null && !typed) {
            text = d != null ? d.text : remote.optString("text");
            baseRevision = d != null ? d.serverRevision : remote.optLong("revision");
            baseAnswerVersion = q.optLong("answerVersion");
            setting = true;
            input.setText(text);
            setting = false;
        }
        if (status != null)
            status.setText(
                    notice.isEmpty() ? "편집 중인 원고는 이 기기에 저장돼요. 공개 버튼을 눌러야 학생에게 보여요." : notice);
    }

    @Override
    protected void render() {
        if (shell == null || repo == null || built) return;
        JSONObject q = item("QUESTION", screen.questionId);
        if (!draftsLoaded || q.optString("id").isEmpty() && q.optString("text").isEmpty()) {
            clear();
            ui.note(shell.content, "접수된 질문을 불러오고 있어요.");
            showNotice();
            return;
        }
        clear();
        built = true;
        ui.heading(shell.content, q.optString("text"), topicTitle(q.optString("topicId")));
        publicAnswer =
                ui.note(
                        shell.content,
                        q.optString("answer").isEmpty()
                                ? "아직 공개된 답변이 없어요."
                                : q.optString("answer"));
        if (owner()) {
            JSONObject remote = item("ANSWER_DRAFT", "answer_" + screen.questionId);
            OnlineDatabase.Draft d = draft("ANSWER_DRAFT", screen.questionId);
            if (!typed) {
                text = d != null ? d.text : remote.optString("text");
                baseRevision = d != null ? d.serverRevision : remote.optLong("revision");
                baseAnswerVersion = q.optLong("answerVersion");
            }
            input = ui.field(shell.content, "교수 답변 원고", text, true);
            input.addTextChangedListener(
                    new TextWatcher() {
                        public void beforeTextChanged(CharSequence s, int a, int c, int d) {}

                        public void onTextChanged(CharSequence s, int a, int b, int c) {
                            if (setting) return;
                            text = s.toString();
                            typed = true;
                            handler.removeCallbacks(saveLocal);
                            handler.postDelayed(saveLocal, 600);
                        }

                        public void afterTextChanged(Editable e) {}
                    });
            status = ui.note(shell.content, "원고 저장과 답변 공개는 별개예요.");
            ui.button(shell.dock, "교수 초안 서버 저장", false, () -> send(false));
            ui.button(shell.dock, "답변 공개", true, () -> send(true));
        }
    }

    private void flush() {
        if (repo != null && input != null && typed)
            repo.saveAnswerLocal(
                    screen.lessonId,
                    screen.questionId,
                    input.getText().toString(),
                    baseRevision,
                    baseAnswerVersion);
    }

    private void send(boolean publish) {
        if (busy || input == null) return;
        String sent = input.getText().toString();
        if (publish && sent.trim().isEmpty()) {
            error("공개할 답변을 입력해 주세요.");
            return;
        }
        handler.removeCallbacks(saveLocal);
        busy = true;
        final long token = viewToken();
        repo.answer(
                screen.lessonId,
                screen.questionId,
                sent,
                publish,
                baseRevision,
                baseAnswerVersion,
                new OnlineRepository.Callback() {
                    public void success(JSONObject v) {
                        busy = false;
                        if (!current(token) || input == null) return;
                        JSONObject d = v.optJSONObject(publish ? "draft" : "document");
                        if (d != null) {
                            baseRevision = d.optLong("revision");
                            baseAnswerVersion = d.optLong("baseAnswerVersion");
                        }
                        if (input.getText().toString().equals(sent)) {
                            typed = false;
                            setting = true;
                            input.setText(publish ? "" : sent);
                            setting = false;
                            text = publish ? "" : sent;
                        }
                        changed();
                    }

                    public void error(String r, String m) {
                        busy = false;
                        if (current(token)) OnlineQuestionDetailFragment.this.error(m);
                    }
                });
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(saveLocal);
        flush();
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("text", input == null ? text : input.getText().toString());
        out.putBoolean("typed", typed);
        out.putLong("base_revision", baseRevision);
        out.putLong("answer_version", baseAnswerVersion);
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(saveLocal);
        input = null;
        publicAnswer = null;
        status = null;
        built = false;
        busy = false;
        super.onDestroyView();
    }
}
