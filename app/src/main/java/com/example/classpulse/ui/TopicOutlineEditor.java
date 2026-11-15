package com.example.classpulse.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.PopupMenu;
import com.example.classpulse.R;
import com.example.classpulse.domain.Models;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Edits shared topic models in place and keeps children with their parent when reordering. */
public final class TopicOutlineEditor {
    private static final int MAX_TITLE_LENGTH = 120;
    private final LinearLayout list;
    private final LayoutInflater inflater;
    private final List<Models.Topic> outline;
    private final boolean editable;
    private final int maxTopics;
    private final MaterialButton addParent;
    private final Map<String, EditText> inputs = new LinkedHashMap<>();

    public TopicOutlineEditor(LinearLayout content, List<Models.Topic> outline, boolean editable) {
        this(content, outline, editable, 100);
    }

    public TopicOutlineEditor(
            LinearLayout content, List<Models.Topic> outline, boolean editable, int maxTopics) {
        Context context = content.getContext();
        this.outline = outline;
        this.editable = editable;
        this.maxTopics = maxTopics;
        inflater = LayoutInflater.from(context);
        Ui ui = new Ui(context);
        ui.section(content, "수업 목차", null);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParent = ui.button(content, "+ 대주제 추가", false, () -> addTopic(null));
        rebuild(null);
    }

    private static final class Group {
        final Models.Topic parent;
        final List<Models.Topic> children = new ArrayList<>();

        Group(Models.Topic parent) {
            this.parent = parent;
        }
    }

    private List<Group> groups() {
        Map<String, Group> byId = new LinkedHashMap<>();
        for (Models.Topic topic : outline) {
            if (topic.parentTopicId == null || topic.parentTopicId.isEmpty()) {
                byId.put(topic.id, new Group(topic));
            }
        }
        for (Models.Topic topic : outline) {
            Group group = byId.get(topic.parentTopicId);
            if (group != null) group.children.add(topic);
        }
        return new ArrayList<>(byId.values());
    }

    private void rebuild(String newTopicId) {
        View focus = list.findFocus();
        int selectedStart = -1;
        int selectedEnd = -1;
        String focusedId = null;
        for (Map.Entry<String, EditText> entry : inputs.entrySet()) {
            if (entry.getValue() == focus) {
                focusedId = entry.getKey();
                selectedStart = entry.getValue().getSelectionStart();
                selectedEnd = entry.getValue().getSelectionEnd();
                break;
            }
        }
        list.removeAllViews();
        inputs.clear();
        addParent.setEnabled(editable && outline.size() < maxTopics);
        List<Group> groups = groups();
        for (int i = 0; i < groups.size(); i++) {
            int parentIndex = i;
            String number = Integer.toString(i + 1);
            Group group = groups.get(i);
            View card = inflater.inflate(R.layout.block_outline_group, list, false);
            LinearLayout parentRow = card.findViewById(R.id.outline_parent_topic);
            LinearLayout childRows = card.findViewById(R.id.outline_child_topics);
            MaterialButton addChild = card.findViewById(R.id.outline_add_child);
            addRow(parentRow, group.parent, number, true, i, groups.size(),
                    direction -> moveGroup(groups, parentIndex, direction),
                    () -> removeGroup(groups, group));
            for (int j = 0; j < group.children.size(); j++) {
                int childIndex = j;
                Models.Topic child = group.children.get(j);
                addRow(childRows, child, number + "." + (j + 1), false, j, group.children.size(),
                        direction -> moveChild(groups, group, childIndex, direction),
                        () -> removeChild(groups, group, child));
            }
            addChild.setText("+ 소주제 추가");
            addChild.setContentDescription(number + ". 대주제에 소주제 추가");
            addChild.setEnabled(editable && outline.size() < maxTopics);
            addChild.setOnClickListener(v -> addTopic(group.parent.id));
            list.addView(card);
        }
        EditText target = inputs.get(newTopicId == null ? focusedId : newTopicId);
        if (target == null || !target.isEnabled() || !target.requestFocus()) return;
        if (newTopicId == null && selectedStart >= 0 && selectedEnd >= 0) {
            int length = target.length();
            target.setSelection(Math.min(selectedStart, length), Math.min(selectedEnd, length));
        } else if (newTopicId != null) {
            target.post(() -> {
                if (target.isAttachedToWindow() && target.hasFocus()) {
                    target.getContext().getSystemService(InputMethodManager.class)
                            .showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }
    }

    private void addRow(
            LinearLayout container, Models.Topic topic, String number, boolean parent,
            int index, int siblingCount, java.util.function.IntConsumer move, Runnable remove) {
        View row = inflater.inflate(R.layout.block_outline_row, container, false);
        TextView label = row.findViewById(R.id.outline_number);
        EditText input = row.findViewById(R.id.outline_title);
        ImageButton actions = row.findViewById(R.id.outline_actions);
        String name = parent ? number + ". 대주제" : number + " 소주제";
        label.setText(number);
        input.setId(View.generateViewId());
        input.setContentDescription(name);
        input.setHint(parent ? "대주제 제목" : "소주제 제목");
        input.setText(topic.title);
        input.setTextSize(parent ? 16 : 15);
        if (parent) input.setTypeface(input.getTypeface(), Typeface.BOLD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TITLE_LENGTH)});
        input.setEnabled(editable);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                topic.title = s.toString();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        inputs.put(topic.id, input);
        actions.setContentDescription(name + " 더보기");
        // The only remaining parent has neither a neighbor nor a valid remove action.
        actions.setVisibility(parent && siblingCount == 1 ? View.INVISIBLE : View.VISIBLE);
        actions.setEnabled(editable);
        actions.setOnClickListener(anchor -> {
            PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
            if (index > 0) menu.getMenu().add("위로 이동").setOnMenuItemClickListener(item -> {
                move.accept(-1);
                return true;
            });
            if (index + 1 < siblingCount) menu.getMenu().add("아래로 이동").setOnMenuItemClickListener(item -> {
                move.accept(1);
                return true;
            });
            menu.getMenu().add(parent ? "대주제 삭제" : "소주제 삭제")
                    .setOnMenuItemClickListener(item -> {
                        remove.run();
                        return true;
                    });
            menu.show();
        });
        container.addView(row);
    }

    private void addTopic(String parentId) {
        if (!editable || outline.size() >= maxTopics) return;
        Models.Topic topic = new Models.Topic();
        topic.id = UUID.randomUUID().toString();
        topic.parentTopicId = parentId;
        List<Group> groups = groups();
        if (parentId == null) groups.add(new Group(topic));
        else for (Group group : groups) {
            if (parentId.equals(group.parent.id)) group.children.add(topic);
        }
        apply(groups, topic.id);
    }

    private void moveGroup(List<Group> groups, int index, int direction) {
        Collections.swap(groups, index, index + direction);
        apply(groups, null);
    }

    private void moveChild(List<Group> groups, Group group, int index, int direction) {
        Collections.swap(group.children, index, index + direction);
        apply(groups, null);
    }

    private void removeGroup(List<Group> groups, Group group) {
        String message = group.children.isEmpty() ? "이 대주제를 목차에서 뺄까요?"
                : "이 대주제와 아래 소주제 " + group.children.size() + "개를 목차에서 뺄까요?";
        confirmRemoval(message, () -> {
            groups.remove(group);
            apply(groups, null);
        });
    }

    private void removeChild(List<Group> groups, Group group, Models.Topic child) {
        confirmRemoval("이 소주제를 목차에서 뺄까요?", () -> {
            group.children.remove(child);
            apply(groups, null);
        });
    }

    private void confirmRemoval(String message, Runnable remove) {
        new MaterialAlertDialogBuilder(list.getContext())
                .setTitle("주제 삭제")
                .setMessage(message)
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    if (list.isAttachedToWindow()) remove.run();
                })
                .show();
    }

    private void apply(List<Group> groups, String focusId) {
        outline.clear();
        for (Group group : groups) {
            outline.add(group.parent);
            outline.addAll(group.children);
        }
        for (int i = 0; i < outline.size(); i++) outline.get(i).position = i;
        rebuild(focusId);
    }
}
