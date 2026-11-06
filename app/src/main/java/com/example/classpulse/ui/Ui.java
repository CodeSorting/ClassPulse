package com.example.classpulse.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.example.classpulse.R;
import com.example.classpulse.databinding.*;
import com.example.classpulse.domain.Models;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/** Common views are inflated from XML, keeping the v6 visual language in one place. */
public final class Ui {
    private final Context context;
    private final LayoutInflater inflater;

    public Ui(Context context) {
        this.context = context;
        inflater = LayoutInflater.from(context);
    }

    private int color(int id) {
        return ContextCompat.getColor(context, id);
    }

    private void add(LinearLayout parent, View child) {
        parent.setVisibility(View.VISIBLE);
        parent.addView(child);
    }

    private void optional(TextView view, String value) {
        view.setText(value);
        view.setVisibility(value == null || value.trim().isEmpty() ? View.GONE : View.VISIBLE);
    }

    public TextView heading(LinearLayout parent, String title, String subtitle) {
        BlockHeadingBinding b = BlockHeadingBinding.inflate(inflater, parent, false);
        b.title.setText(title);
        optional(b.subtitle, subtitle);
        add(parent, b.getRoot());
        return b.title;
    }

    public TextView text(LinearLayout parent, String value) {
        TextView v = (TextView) inflater.inflate(R.layout.block_text, parent, false);
        v.setText(value);
        add(parent, v);
        return v;
    }

    public TextView note(LinearLayout parent, String value) {
        BlockNoteBinding b = BlockNoteBinding.inflate(inflater, parent, false);
        b.noteText.setText(value);
        add(parent, b.getRoot());
        return b.noteText;
    }

    private void styleButton(MaterialButton button, String label, boolean primary, Runnable click) {
        button.setText(label);
        button.setTextColor(color(primary ? R.color.cp_surface : R.color.cp_brand));
        button.setBackgroundTintList(
                ColorStateList.valueOf(color(primary ? R.color.cp_brand : R.color.cp_mint)));
        button.setStrokeWidth(
                primary ? 0 : Math.round(context.getResources().getDisplayMetrics().density));
        button.setStrokeColor(ColorStateList.valueOf(color(R.color.cp_line)));
        button.setOnClickListener(v -> {
            if (click != null) click.run();
        });
    }

    public MaterialButton button(
            LinearLayout parent, String label, boolean primary, Runnable click) {
        MaterialButton b = (MaterialButton) inflater.inflate(R.layout.block_button, parent, false);
        styleButton(b, label, primary, click);
        add(parent, b);
        return b;
    }

    public MaterialButton googleButton(LinearLayout parent, String label, Runnable click) {
        MaterialButton button = button(parent, label, false, click);
        button.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.WHITE));
        button.setTextColor(android.graphics.Color.rgb(31, 31, 31));
        button.setStrokeColor(ColorStateList.valueOf(android.graphics.Color.rgb(116, 119, 117)));
        button.setStrokeWidth(
                Math.max(1, Math.round(context.getResources().getDisplayMetrics().density)));
        button.setIcon(ContextCompat.getDrawable(context, R.drawable.google_sign_in_g));
        button.setIconTint(null);
        button.setIconSize(Math.round(20 * context.getResources().getDisplayMetrics().density));
        button.setIconPadding(Math.round(12 * context.getResources().getDisplayMetrics().density));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setTextSize(14);
        return button;
    }

    public void pair(
            LinearLayout parent,
            String left,
            Runnable leftClick,
            String right,
            Runnable rightClick) {
        pair(parent, left, leftClick, right, rightClick, false);
    }

    public void pair(
            LinearLayout parent,
            String left,
            Runnable leftClick,
            String right,
            Runnable rightClick,
            boolean rightPrimary) {
        BlockPairBinding b = BlockPairBinding.inflate(inflater, parent, false);
        styleButton(b.left, left, left.startsWith("✓"), leftClick);
        styleButton(b.right, right, rightPrimary || right.startsWith("✓"), rightClick);
        add(parent, b.getRoot());
    }

    public MaterialCardView card(
            LinearLayout parent, String title, String subtitle, String badge, Runnable click) {
        BlockCardBinding b = BlockCardBinding.inflate(inflater, parent, false);
        b.title.setText(title);
        optional(b.subtitle, subtitle);
        optional(b.badge, badge);
        if (click != null) {
            b.getRoot().setClickable(true);
            b.getRoot().setFocusable(true);
            b.getRoot().setOnClickListener(v -> click.run());
        }
        add(parent, b.getRoot());
        return b.getRoot();
    }

    public EditText field(LinearLayout parent, String label, String value, boolean multiline) {
        TextInputLayout root =
                (TextInputLayout)
                        inflater.inflate(
                                multiline ? R.layout.block_editor : R.layout.block_field,
                                parent,
                                false);
        root.setHint(label);
        EditText input = root.findViewById(R.id.input);
        input.setText(value == null ? "" : value);
        input.setId(View.generateViewId());
        input.setContentDescription(label);
        input.setInputType(
                multiline
                        ? InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        add(parent, root);
        return input;
    }

    public void topic(
            LinearLayout parent, String title, String status, boolean selected, Runnable click) {
        BlockTopicBinding b = BlockTopicBinding.inflate(inflater, parent, false);
        b.title.setText(title);
        b.status.setText(status);
        b.getRoot().setCardBackgroundColor(color(selected ? R.color.cp_mint : R.color.cp_surface));
        b.getRoot().setStrokeColor(color(selected ? R.color.cp_accent : R.color.cp_line));
        if (click != null) {
            b.getRoot().setClickable(true);
            b.getRoot().setFocusable(true);
            b.getRoot().setOnClickListener(v -> click.run());
        }
        add(parent, b.getRoot());
    }

    public void stats(
            LinearLayout parent, String labelA, String valueA, String labelB, String valueB) {
        BlockStatsBinding b = BlockStatsBinding.inflate(inflater, parent, false);
        b.labelA.setText(labelA);
        b.valueA.setText(valueA);
        b.labelB.setText(labelB);
        b.valueB.setText(valueB);
        add(parent, b.getRoot());
    }

    public void divider(LinearLayout parent) {
        add(parent, inflater.inflate(R.layout.block_divider, parent, false));
    }

    public void chooseTopic(
            Models.Lesson lesson, String selected, Consumer<Models.Topic> callback) {
        if (lesson == null || lesson.topics == null || lesson.topics.isEmpty()) return;
        List<Models.Topic> topics = new ArrayList<>(lesson.topics);
        String[] labels = new String[topics.size()];
        int checked = 0;
        for (int i = 0; i < topics.size(); i++) {
            Models.Topic t = topics.get(i);
            labels[i] = (i + 1) + ". " + t.title + (t.completed ? " · 완료" : "");
            if (t.id.equals(selected)) checked = i;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle("질문할 대주제")
                .setSingleChoiceItems(
                        labels,
                        checked,
                        (dialog, which) -> {
                            dialog.dismiss();
                            callback.accept(topics.get(which));
                        })
                .setNegativeButton("취소", null)
                .show();
    }

    public void prompt(String title, String initial, boolean multiline, Consumer<String> confirm) {
        TextInputLayout root =
                (TextInputLayout)
                        inflater.inflate(
                                multiline ? R.layout.block_editor : R.layout.block_field,
                                null,
                                false);
        root.setHint(title);
        EditText input = root.findViewById(R.id.input);
        input.setText(initial == null ? "" : initial);
        if (!multiline) input.setSingleLine(true);
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(root)
                .setNegativeButton("취소", null)
                .setPositiveButton(
                        "저장", (d, w) -> confirm.accept(input.getText().toString().trim()))
                .show();
    }

    public static String topicTitle(Models.Lesson lesson, String id) {
        Models.Topic t = lesson == null ? null : lesson.topic(id);
        return t == null ? "대주제 미선택" : t.title;
    }

    public static String time(long timestamp) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(timestamp));
    }
}
