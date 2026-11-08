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

    /** 화면 중간의 소제목. 맨 위 큰 제목(heading)과 구분되도록 작게 표시한다. */
    public TextView section(LinearLayout parent, String title, String subtitle) {
        BlockSectionBinding b = BlockSectionBinding.inflate(inflater, parent, false);
        b.title.setText(title);
        optional(b.subtitle, subtitle);
        add(parent, b.getRoot());
        return b.title;
    }

    /** 시작 화면의 기능 소개 한 줄. */
    public void feature(LinearLayout parent, int icon, String title, String subtitle) {
        BlockFeatureBinding b = BlockFeatureBinding.inflate(inflater, parent, false);
        b.icon.setImageResource(icon);
        b.title.setText(title);
        optional(b.subtitle, subtitle);
        add(parent, b.getRoot());
    }

    /** 참여 코드를 한 자리씩 큰 상자로 보여준다. 앞자리 0도 그대로 표시된다. */
    public void code(LinearLayout parent, String code, String caption, String hint) {
        BlockCodeBinding b = BlockCodeBinding.inflate(inflater, parent, false);
        optional(b.caption, caption);
        optional(b.hint, hint);
        String value = code == null ? "" : code;
        for (int i = 0; i < value.length(); i++) {
            TextView digit = new TextView(context);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, dp(68), 1f);
            if (i > 0) lp.setMarginStart(dp(8));
            digit.setLayoutParams(lp);
            digit.setGravity(android.view.Gravity.CENTER);
            digit.setText(String.valueOf(value.charAt(i)));
            digit.setTextSize(30);
            digit.setTypeface(null, android.graphics.Typeface.BOLD);
            digit.setTextColor(color(R.color.cp_brand));
            digit.setBackgroundResource(R.drawable.bg_cp_digit);
            digit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            b.digits.addView(digit);
        }
        b.digits.setContentDescription((caption == null ? "참여 코드" : caption) + " " + value);
        add(parent, b.getRoot());
    }

    /** 퀴즈 보기처럼 하나를 고르는 선택지. 왼쪽 정렬 + 선택 여부 아이콘. */
    public MaterialButton choice(
            LinearLayout parent, String label, boolean selected, Runnable click) {
        MaterialButton b = button(parent, label, selected, click);
        b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        b.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        b.setIcon(ContextCompat.getDrawable(
                context, selected ? R.drawable.ic_cp_done : R.drawable.ic_cp_todo));
        b.setIconGravity(MaterialButton.ICON_GRAVITY_START);
        b.setIconSize(dp(20));
        b.setIconPadding(dp(12));
        b.setIconTint(b.getTextColors());
        b.setPaddingRelative(dp(16), b.getPaddingTop(), dp(16), b.getPaddingBottom());
        return b;
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

    /** 수업 종료·시연 종료·로그아웃처럼 되돌리기 어려운 동작인지. */
    private static boolean destructive(String label) {
        return label != null
                && (label.endsWith("종료") || label.contains("로그아웃") || label.contains("삭제"))
                && !label.endsWith("…");
    }

    private void styleButton(MaterialButton button, String label, boolean primary, Runnable click) {
        button.setText(label);
        boolean danger = !primary && destructive(label);
        button.setTextColor(
                color(primary ? R.color.cp_surface : danger ? R.color.cp_danger : R.color.cp_brand));
        button.setBackgroundTintList(
                ColorStateList.valueOf(
                        color(primary ? R.color.cp_brand : danger ? R.color.cp_danger_soft : R.color.cp_mint)));
        button.setStrokeWidth(
                primary ? 0 : Math.round(context.getResources().getDisplayMetrics().density));
        button.setStrokeColor(
                ColorStateList.valueOf(color(danger ? R.color.cp_danger_soft : R.color.cp_line)));
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
        reactionIcon(b.left);
        reactionIcon(b.right);
        add(parent, b.getRoot());
    }

    /** 네 가지 익명 반응의 아이콘. 반응 이름이 아니면 0을 돌려준다. */
    public static int reactionIconRes(String label) {
        if (label == null) return 0;
        if (label.contains("이해가 안")) return R.drawable.ic_cp_confused;
        if (label.contains("너무 빨라")) return R.drawable.ic_cp_fast;
        if (label.contains("용어가 어려")) return R.drawable.ic_cp_term;
        if (label.contains("계산을 놓쳤")) return R.drawable.ic_cp_calc;
        return 0;
    }

    /** 반응 버튼이면 글자 위에 아이콘을 얹어 2×2 타일처럼 보이게 한다. */
    private void reactionIcon(MaterialButton button) {
        int icon = reactionIconRes(button.getText().toString());
        if (icon == 0) return;
        button.setIcon(ContextCompat.getDrawable(context, icon));
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TOP);
        button.setIconSize(dp(22));
        button.setIconPadding(dp(4));
        button.setIconTint(button.getTextColors());
        button.setMinHeight(dp(72));
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** 상태 문구에 맞춰 배지의 배경색·글자색을 고른다. 초록=진행/완료 공개, 노랑=대기, 빨강=확인 필요, 회색=종료·예정. */
    public void tone(TextView chip, String label) {
        int bg = R.color.cp_mint, fg = R.color.cp_accent;
        String t = label == null ? "" : label;
        if (t.contains("확인 필요") || t.contains("충돌") || t.contains("실패")) {
            bg = R.color.cp_danger_soft;
            fg = R.color.cp_danger;
        } else if (t.contains("대기") || t.contains("시작 전") || t.contains("전송 중")
                || t.contains("초안") || t.contains("기기에 저장") || t.contains("재전송")
                || t.contains("미공개") || t.contains("비공개") || t.contains("작성 중")) {
            bg = R.color.cp_warning;
            fg = R.color.cp_warning_ink;
        } else if (t.contains("종료") || t.contains("예정") || t.contains("나만 보는")) {
            bg = R.color.cp_neutral_soft;
            fg = R.color.cp_muted;
        }
        chip.setBackgroundTintList(ColorStateList.valueOf(color(bg)));
        chip.setTextColor(color(fg));
    }

    public MaterialCardView card(
            LinearLayout parent, String title, String subtitle, String badge, Runnable click) {
        BlockCardBinding b = BlockCardBinding.inflate(inflater, parent, false);
        b.title.setText(title);
        optional(b.subtitle, subtitle);
        optional(b.badge, badge);
        tone(b.badge, badge);
        if (click != null) {
            b.getRoot().setClickable(true);
            b.getRoot().setFocusable(true);
            b.getRoot().setOnClickListener(v -> click.run());
            b.chevron.setVisibility(View.VISIBLE);
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
        optional(b.status, status);
        tone(b.status, status);
        String st = status == null ? "" : status;
        boolean live = st.contains("진행");
        boolean done = st.contains("완료");
        b.icon.setImageResource(
                live ? R.drawable.ic_cp_live : done ? R.drawable.ic_cp_done : R.drawable.ic_cp_todo);
        b.icon.setImageTintList(
                ColorStateList.valueOf(color(live || done ? R.color.cp_accent : R.color.cp_muted)));
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

    /** 반응 유형 하나의 건수를 막대로 표시한다. max는 막대 100%에 해당하는 값. */
    public void meter(LinearLayout parent, String label, int value, int max) {
        meter(parent, label, value + "건", value, max);
    }

    /** valueText가 null이면 오른쪽 숫자 없이 label만 보여준다. */
    public void meter(LinearLayout parent, String label, String valueText, int value, int max) {
        BlockMeterBinding b = BlockMeterBinding.inflate(inflater, parent, false);
        int icon = reactionIconRes(label);
        if (icon != 0) b.icon.setImageResource(icon);
        else b.icon.setVisibility(View.GONE);
        b.label.setText(label);
        optional(b.value, valueText);
        b.bar.setMax(Math.max(1, max));
        b.bar.setProgressCompat(Math.max(0, Math.min(value, Math.max(1, max))), false);
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
