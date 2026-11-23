package com.example.classpulse.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.ListPopupWindow;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.example.classpulse.R;
import com.example.classpulse.databinding.BlockStudentDockBinding;
import com.example.classpulse.databinding.BlockStudentLessonBinding;
import com.example.classpulse.databinding.BlockStudentTopicRowBinding;
import com.example.classpulse.domain.Models;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persistent student input views shared by online classes and the local demonstration. */
public final class StudentLessonView {
    public interface Result {
        void success();
        void error(String message);
    }

    public interface Actions {
        void react(String type);
        void save(String id, String topic, String text, boolean submit, Result result);
        void edit(String id, String topic);
        void error(String message);
    }

    public static final class Topic {
        public final String id, parentId, title;
        public final boolean completed, current;

        public Topic(String id, String parentId, String title, boolean completed, boolean current) {
            this.id = id;
            this.parentId = parentId;
            this.title = title;
            this.completed = completed;
            this.current = current;
        }
    }

    private final Context context;
    private final Actions actions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable save = () -> persist(false, false);
    private final LayoutInflater inflater;
    private BlockStudentLessonBinding body;
    private BlockStudentDockBinding dock;
    private ListPopupWindow popup;
    private List<Topic> topics = Collections.emptyList();
    private final List<Topic> roots = new ArrayList<>();
    private String id = UUID.randomUUID().toString(), text = "", topic = "", currentRoot = "";
    private boolean pinned, sending, setting, accepting;

    public StudentLessonView(Context context, Bundle state, Actions actions) {
        this.context = context;
        this.actions = actions;
        inflater = LayoutInflater.from(context);
        if (state != null) {
            id = state.getString("id", id);
            text = state.getString("text", "");
            topic = state.getString("topic", "");
            pinned = state.getBoolean("pinned");
        }
    }

    public void attach(LinearLayout content, LinearLayout bottom) {
        if (body == null) body = BlockStudentLessonBinding.inflate(inflater, content, false);
        if (dock == null) {
            dock = BlockStudentDockBinding.inflate(inflater, bottom, false);
            dock.studentQuestionInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
            dock.studentQuestionInput.setText(text);
            dock.studentQuestionInput.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void afterTextChanged(Editable value) {}
                public void onTextChanged(CharSequence value, int start, int before, int count) {
                    if (setting) return;
                    text = value.toString();
                    if (!text.trim().isEmpty()) pinned = true;
                    handler.removeCallbacks(save);
                    if (!text.trim().isEmpty()) handler.postDelayed(save, 900);
                    updateComposer();
                }
            });
            dock.studentQuestionInput.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_SEND) return false;
                persist(true, false);
                return true;
            });
            dock.studentQuestionSend.setOnClickListener(view -> persist(true, false));
            dock.studentQuestionDetail.setOnClickListener(view -> persist(false, true));
            dock.studentQuestionTopic.setOnClickListener(view -> chooseTopic());
            ViewCompat.setOnApplyWindowInsetsListener(dock.getRoot(), (view, insets) -> {
                if (dock != null) dock.studentReactionsCard.setVisibility(
                        insets.isVisible(WindowInsetsCompat.Type.ime()) ? View.GONE : View.VISIBLE);
                return insets;
            });
        }
        if (body.getRoot().getParent() == null) content.addView(body.getRoot());
        if (dock.getRoot().getParent() == null) bottom.addView(dock.getRoot());
        bottom.setVisibility(View.VISIBLE);
        ViewCompat.requestApplyInsets(dock.getRoot());
    }

    public LinearLayout records() {
        return body.studentRecords;
    }

    public String draftId() {
        return id;
    }

    public boolean editingDraft(String draftId) {
        return id.equals(draftId) && !text.trim().isEmpty();
    }

    public void update(String title, String state, List<Topic> outline, Set<String> selected,
                       Set<String> pending, boolean questionsOpen, boolean reactionsOpen) {
        topics = outline;
        roots.clear();
        currentRoot = "";
        for (Topic value : outline) {
            if (value.parentId == null) roots.add(value);
            if (value.current) currentRoot = value.parentId == null ? value.id : value.parentId;
        }
        if (!pinned || topic.isEmpty()) {
            if (!currentRoot.isEmpty()) topic = currentRoot;
            else if (!roots.isEmpty()) topic = roots.get(0).id;
        }
        accepting = questionsOpen;
        body.studentCourseTitle.setText(title);
        body.studentCourseState.setText(Models.LIVE.equals(state) ? "● 수업 중" : "시작 전");
        renderOutline();
        bindReaction(dock.studentReactionUnderstood, Models.UNDERSTOOD, selected, pending, reactionsOpen);
        bindReaction(dock.studentReactionUnderstanding, Models.UNDERSTANDING, selected, pending, reactionsOpen);
        bindReaction(dock.studentReactionSpeed, Models.SPEED, selected, pending, reactionsOpen);
        bindReaction(dock.studentReactionTerm, Models.TERM, selected, pending, reactionsOpen);
        bindReaction(dock.studentReactionCalculation, Models.CALCULATION, selected, pending, reactionsOpen);
        String hint = !questionsOpen ? "교수님이 질문·반응 접수를 마감했어요."
                : !reactionsOpen ? "현재는 반응을 보낼 수 없어요." : "";
        dock.studentReactionHint.setText(hint);
        dock.studentReactionHint.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
        updateComposer();
    }

    private void renderOutline() {
        body.studentOutline.removeAllViews();
        for (int index = 0; index < roots.size(); index++) {
            Topic root = roots.get(index);
            List<Topic> children = new ArrayList<>();
            for (Topic value : topics) if (root.id.equals(value.parentId)) children.add(value);
            boolean completed = root.completed;
            if (!children.isEmpty()) {
                completed = true;
                for (Topic child : children) completed &= child.completed;
            }
            if (index > 0) {
                View divider = new View(context);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                params.setMargins(0, dp(10), 0, dp(8));
                divider.setBackgroundColor(color(R.color.cp_line));
                body.studentOutline.addView(divider, params);
            }
            topicRow((index + 1) + ". " + root.title, false, completed, root.current && children.isEmpty());
            for (int sub = 0; sub < children.size(); sub++) {
                Topic child = children.get(sub);
                topicRow((index + 1) + "." + (sub + 1) + " " + child.title,
                        true, child.completed, child.current);
            }
        }
    }

    private void topicRow(String title, boolean child, boolean completed, boolean current) {
        BlockStudentTopicRowBinding row = BlockStudentTopicRowBinding.inflate(inflater, body.studentOutline, false);
        row.studentTopicTitle.setText(title);
        row.studentTopicTitle.setPadding(current ? dp(8) : 0, dp(8), 0, dp(8));
        row.studentTopicTitle.setTypeface(null, !child || current ? Typeface.BOLD : Typeface.NORMAL);
        row.studentTopicBranch.setVisibility(child ? View.VISIBLE : View.GONE);
        row.studentTopicDone.setVisibility(completed ? View.VISIBLE : View.GONE);
        row.studentTopicActiveLine.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
        row.studentTopicState.setVisibility(current ? View.VISIBLE : View.GONE);
        row.studentTopicBody.setBackgroundResource(current ? R.drawable.bg_student_topic_active : 0);
        row.getRoot().setContentDescription(title + (current ? ", 진행 중" : completed ? ", 완료" : ", 예정"));
        body.studentOutline.addView(row.getRoot());
    }

    private void bindReaction(MaterialButton button, String type, Set<String> selected,
                              Set<String> pending, boolean open) {
        boolean checked = selected.contains(type);
        button.setText((checked ? "✓ " : "") + Ui.reactionLabel(type));
        button.setSelected(checked);
        button.setEnabled(open && !pending.contains(type));
        button.setBackgroundTintList(new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_pressed}, new int[]{}},
                new int[]{color(R.color.cp_neutral_soft), color(R.color.cp_control_pressed),
                        color(checked ? R.color.cp_mint : R.color.cp_surface)}));
        button.setStrokeColor(ColorStateList.valueOf(color(checked ? R.color.cp_accent : R.color.cp_line)));
        button.setOnClickListener(view -> actions.react(type));
    }

    private void updateComposer() {
        if (dock == null) return;
        Topic selected = find(topic);
        Topic root = selected == null ? null : selected.parentId == null ? selected : find(selected.parentId);
        int number = root == null ? 0 : roots.indexOf(root) + 1;
        String label = selected == null ? "대주제를 선택해 주세요"
                : number + ". " + (selected.parentId == null ? selected.title
                : root == null ? selected.title : root.title + " › " + selected.title);
        dock.studentQuestionCaption.setText(!accepting
                ? "질문 접수 마감 · 작성 내용은 초안으로 저장돼요" : "질문할 대주제 · " + label);
        dock.studentQuestionTopic.setText(number == 0 ? "–" : String.valueOf(number));
        dock.studentQuestionTopic.setContentDescription("질문할 대주제 선택, " + label);
        boolean canSend = accepting && !sending && selected != null && !text.trim().isEmpty();
        dock.studentQuestionSend.setEnabled(canSend);
        dock.studentQuestionSend.setAlpha(canSend ? 1f : 0.45f);
        dock.studentQuestionTopic.setEnabled(!sending && !roots.isEmpty());
        dock.studentQuestionDetail.setEnabled(!sending && selected != null);
        dock.studentQuestionInput.setEnabled(!sending);
    }

    private void chooseTopic() {
        if (popup != null) popup.dismiss();
        popup = new ListPopupWindow(context);
        ArrayAdapter<Topic> adapter = new ArrayAdapter<Topic>(context, R.layout.block_student_topic_option, roots) {
            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
                TextView view = (TextView) (recycled == null
                        ? inflater.inflate(R.layout.block_student_topic_option, parent, false) : recycled);
                Topic value = getItem(position);
                view.setText(String.valueOf(position + 1));
                Topic selected = find(topic);
                String rootId = selected == null ? "" : selected.parentId == null ? selected.id : selected.parentId;
                boolean selectedRow = value.id.equals(rootId);
                view.setBackgroundResource(selectedRow ? R.drawable.bg_student_topic_selected : R.drawable.bg_student_topic_number);
                view.setTextColor(color(selectedRow ? R.color.cp_surface : R.color.cp_ink));
                view.setContentDescription("질문 대주제 " + (position + 1) + ", " + value.title);
                return view;
            }
        };
        popup.setAdapter(adapter);
        popup.setAnchorView(dock.studentQuestionTopic);
        popup.setWidth(dp(76));
        popup.setHeight(dp(Math.min(roots.size(), 5) * 52 + 16));
        popup.setModal(true);
        popup.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_student_popup));
        popup.setOnItemClickListener((parent, view, position, item) -> {
            topic = roots.get(position).id;
            pinned = true;
            updateComposer();
            popup.dismiss();
            if (!text.trim().isEmpty()) {
                handler.removeCallbacks(save);
                handler.postDelayed(save, 900);
            }
        });
        popup.show();
    }

    private void persist(boolean submit, boolean edit) {
        handler.removeCallbacks(save);
        if (sending || find(topic) == null) return;
        if (text.trim().isEmpty()) {
            if (edit) {
                String question = UUID.randomUUID().toString(), chosenTopic = topic;
                resetQuestion();
                actions.edit(question, chosenTopic);
            }
            return;
        }
        if (submit && !accepting) {
            actions.error("교수님이 질문 접수를 마감했어요.");
            return;
        }
        String draft = id;
        if (submit || edit) sending = true;
        updateComposer();
        actions.save(draft, topic, text, submit, new Result() {
            public void success() {
                if (!draft.equals(id)) return;
                sending = false;
                if (edit) {
                    String question = id, chosenTopic = topic;
                    resetQuestion();
                    actions.edit(question, chosenTopic);
                } else if (submit) {
                    resetQuestion();
                    if (dock != null) Toast.makeText(context, "질문을 저장했어요.", Toast.LENGTH_SHORT).show();
                }
                updateComposer();
            }

            public void error(String message) {
                if (!draft.equals(id)) return;
                sending = false;
                updateComposer();
                if (dock != null) actions.error(message);
            }
        });
    }

    public void acceptDraft(String draftId, boolean closed) {
        if (id.equals(draftId) && !sending && closed) resetQuestion();
    }

    private void resetQuestion() {
        id = UUID.randomUUID().toString();
        text = "";
        topic = currentRoot.isEmpty() && !roots.isEmpty() ? roots.get(0).id : currentRoot;
        pinned = false;
        setInput();
    }

    private void setInput() {
        if (dock == null) return;
        setting = true;
        dock.studentQuestionInput.setText(text);
        setting = false;
        updateComposer();
    }

    public Bundle saveState() {
        Bundle state = new Bundle();
        state.putString("id", id);
        state.putString("text", text);
        state.putString("topic", topic);
        state.putBoolean("pinned", pinned);
        return state;
    }

    public void stop() {
        handler.removeCallbacks(save);
        if (popup != null) popup.dismiss();
        if (!sending && !text.trim().isEmpty()) persist(false, false);
    }

    public void release() {
        stop();
        body = null;
        dock = null;
        popup = null;
    }

    private Topic find(String id) {
        for (Topic value : topics) if (value.id.equals(id)) return value;
        return null;
    }

    private int color(int resource) {
        return ContextCompat.getColor(context, resource);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
