package com.example.classpulse.ui.online;

import android.os.*;
import android.text.*;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import com.example.classpulse.online.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONObject;

public final class OnlineQuestionDetailFragment extends OnlineFragment {
    private EditText input;
    private TextView publicAnswer, status;
    private String text = "";
    private boolean built, typed, setting, busy, withdrawing;
    private MaterialButton withdrawButton;
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
        if (shell == null || showWithdrawn()) return;
        if (built && lesson().optString("id").isEmpty()) {
            built = false;
            clear();
            input = null;
            publicAnswer = null;
            status = null;
            withdrawButton = null;
            ui.note(shell.content, "수업 접근 권한을 확인해 주세요.");
            return;
        }
        if (!built) {
            render();
            return;
        }
        JSONObject q = item("QUESTION", screen.questionId);
        updateWithdrawButton(q);
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
        if (shell == null || repo == null || showWithdrawn() || built) return;
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
            com.google.android.material.button.MaterialButton ai =
                    ui.button(shell.content, "AI 답변 초안 만들기", false, () -> {});
            ai.setOnClickListener(view -> aiText(view, input, "mode", "answer", "questionId", screen.questionId));
            status = ui.note(shell.content, "원고 저장과 답변 공개는 별개예요.");
            ui.button(shell.dock, "교수 초안 서버 저장", false, () -> send(false));
            ui.button(shell.dock, "답변 공개", true, () -> send(true));
        } else if (canWithdraw(q)) {
            withdrawButton = ui.button(shell.dock, "제출 취소", false, this::confirmWithdrawal);
            updateWithdrawButton(q);
        }
    }

    private boolean canWithdraw(JSONObject question) {
        return repo != null && repo.uid.equals(question.optString("authorUid"))
                && "SUBMITTED".equals(question.optString("status")) && question.optLong("answerVersion") == 0;
    }

    private boolean withdrawalPending() {
        for (OnlineDatabase.Outbox operation : operations) {
            if (!"withdrawQuestion".equals(operation.command) || !screen.lessonId.equals(operation.sessionId)) continue;
            if (screen.questionId.equals(OnlineJson.object(operation.payload).optString("id"))
                    && ("PENDING".equals(operation.state) || "SENDING".equals(operation.state))) return true;
        }
        return false;
    }

    private void updateWithdrawButton(JSONObject question) {
        if (withdrawButton == null) return;
        withdrawButton.setVisibility(canWithdraw(question) ? View.VISIBLE : View.GONE);
        boolean pending = withdrawing || withdrawalPending();
        withdrawButton.setEnabled(!pending);
        withdrawButton.setText(pending ? "제출 취소 중…" : "제출 취소");
    }

    private void confirmWithdrawal() {
        if (withdrawing || withdrawalPending() || !canWithdraw(item("QUESTION", screen.questionId))) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("질문 제출을 취소할까요?")
                .setMessage("취소하면 내 질문과 교수님의 접수 목록에서 사라져요.")
                .setNegativeButton("계속 두기", null)
                .setPositiveButton("제출 취소", (dialog, which) -> withdraw())
                .show();
    }

    private void withdraw() {
        long token = viewToken();
        JSONObject question = item("QUESTION", screen.questionId);
        if (!current(token) || withdrawing || withdrawalPending() || !canWithdraw(question)) return;
        withdrawing = true;
        updateWithdrawButton(question);
        repo.withdrawQuestion(screen.lessonId, screen.questionId, new OnlineRepository.Callback() {
            @Override public void success(JSONObject result) {
                withdrawing = false;
                if (current(token)) changed();
            }

            @Override public void error(String reason, String message) {
                withdrawing = false;
                if (current(token)) {
                    changed();
                    OnlineQuestionDetailFragment.this.error(message);
                }
            }
        });
    }

    private boolean showWithdrawn() {
        for (OnlineDatabase.Document document : docs) {
            if (!"QUESTION".equals(document.kind) || !screen.questionId.equals(document.id) || !document.deleted) continue;
            handler.removeCallbacks(saveLocal);
            input = null;
            status = null;
            publicAnswer = null;
            withdrawButton = null;
            built = false;
            clear();
            ui.heading(shell.content, "제출이 취소된 질문이에요", null);
            ui.button(shell.dock, "이전 화면으로", true, () -> host().back());
            return true;
        }
        return false;
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
        withdrawing = false;
        withdrawButton = null;
        super.onDestroyView();
    }
}
