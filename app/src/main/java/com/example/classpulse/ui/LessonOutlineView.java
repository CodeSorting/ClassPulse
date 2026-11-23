package com.example.classpulse.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.example.classpulse.R;
import com.example.classpulse.databinding.BlockLessonOutlineBinding;
import com.example.classpulse.databinding.BlockStudentTopicRowBinding;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The same topic tree for teachers and students, independent of its Room or Firebase source. */
public final class LessonOutlineView {
    private LessonOutlineView() {}

    public static final class Topic {
        public final String id, parentId, title;
        public final boolean completed, current;

        public Topic(String id, String parentId, String title, boolean completed, boolean current) {
            this.id = id;
            this.parentId = parentId == null || parentId.isEmpty() ? null : parentId;
            this.title = title;
            this.completed = completed;
            this.current = current;
        }
    }

    public static void add(LinearLayout parent, List<Topic> topics, @Nullable Consumer<String> revisit) {
        BlockLessonOutlineBinding card = BlockLessonOutlineBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        render(card, topics, revisit);
        parent.addView(card.getRoot());
    }

    public static void render(BlockLessonOutlineBinding card, List<Topic> topics,
                              @Nullable Consumer<String> revisit) {
        LinearLayout rows = card.studentOutline;
        Context context = rows.getContext();
        rows.removeAllViews();
        List<Topic> roots = new ArrayList<>();
        for (Topic topic : topics) if (topic.parentId == null) roots.add(topic);
        for (int index = 0; index < roots.size(); index++) {
            Topic root = roots.get(index);
            List<Topic> children = new ArrayList<>();
            for (Topic topic : topics) if (root.id.equals(topic.parentId)) children.add(topic);
            boolean completed = root.completed;
            if (!children.isEmpty()) {
                completed = true;
                for (Topic child : children) completed &= child.completed;
            }
            if (index > 0) {
                View divider = new View(context);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1));
                params.setMargins(0, dp(context, 10), 0, dp(context, 8));
                divider.setBackgroundColor(ContextCompat.getColor(context, R.color.cp_line));
                rows.addView(divider, params);
            }
            row(rows, root, (index + 1) + ". " + root.title, false, completed,
                    root.current && children.isEmpty(), children.isEmpty() ? revisit : null);
            for (int sub = 0; sub < children.size(); sub++) {
                Topic child = children.get(sub);
                row(rows, child, (index + 1) + "." + (sub + 1) + " " + child.title,
                        true, child.completed, child.current, revisit);
            }
        }
    }

    private static void row(LinearLayout rows, Topic topic, String title, boolean child,
                            boolean completed, boolean current, @Nullable Consumer<String> revisit) {
        Context context = rows.getContext();
        BlockStudentTopicRowBinding row = BlockStudentTopicRowBinding.inflate(
                LayoutInflater.from(context), rows, false);
        row.studentTopicTitle.setText(title);
        row.studentTopicTitle.setPadding(current ? dp(context, 8) : 0,
                dp(context, 8), 0, dp(context, 8));
        row.studentTopicTitle.setTypeface(null, !child || current ? Typeface.BOLD : Typeface.NORMAL);
        row.studentTopicBranch.setVisibility(child ? View.VISIBLE : View.GONE);
        row.studentTopicDone.setVisibility(completed ? View.VISIBLE : View.GONE);
        row.studentTopicActiveLine.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
        row.studentTopicState.setVisibility(current ? View.VISIBLE : View.GONE);
        row.studentTopicBody.setBackgroundResource(current ? R.drawable.bg_student_topic_active : 0);
        String description = title + (current ? ", 진행 중" : completed ? ", 완료" : ", 예정");
        if (revisit != null && completed && !current) {
            row.getRoot().setFocusable(true);
            row.getRoot().setOnClickListener(view -> revisit.accept(topic.id));
            description += ", 눌러서 재설명";
        }
        row.getRoot().setContentDescription(description);
        rows.addView(row.getRoot());
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
