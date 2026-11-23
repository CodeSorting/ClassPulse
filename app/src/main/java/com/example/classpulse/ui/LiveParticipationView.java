package com.example.classpulse.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import com.example.classpulse.R;
import com.example.classpulse.databinding.FragmentShellBinding;
import java.util.*;

/** Student-only live layout. The picker is an overlay, so opening it never moves the composer. */
public final class LiveParticipationView extends FrameLayout {
    public static final class Topic {
        public final String id, parentId, title;
        public final boolean completed;
        public Topic(String id, String parentId, String title, boolean completed) {
            this.id = id; this.parentId = parentId == null ? "" : parentId;
            this.title = title; this.completed = completed;
        }
    }
    public interface Result { void finish(boolean success, String message); }
    public interface Listener {
        void reaction(int index);
        void saveDraft(String id, String topicId, String text);
        void send(String id, String topicId, String text, Result result);
    }

    private final SharedPreferences preferences;
    private final String scope;
    private final Listener listener;
    private final LinearLayout body, outline, reactionBox;
    private final ScrollView outlineScroll, picker;
    private final TextView lessonTitle, topicCaption, topicButton, reactionHeading;
    private final EditText input;
    private final ImageButton send;
    private final TextView[] reactions = new TextView[5];
    private final List<Topic> roots = new ArrayList<>();
    private List<Topic> topics = new ArrayList<>();
    private String currentId = "", selectedId, draftId, outlineKey = "";
    private boolean accepting, sending, changingText, expanded;
    private ValueAnimator animation;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autosave = this::flushDraft;
    private String savedDraft = "";
    private Runnable expandedChanged = () -> {};
    private final int ink, brand, mint, white, line;

    public LiveParticipationView(Context context, String scope, Listener listener) {
        super(context);
        this.scope = scope + "/";
        this.listener = listener;
        preferences = context.getSharedPreferences("live_question_composer", Context.MODE_PRIVATE);
        selectedId = preferences.getString(this.scope + "topic", "");
        draftId = preferences.getString(this.scope + "id", UUID.randomUUID().toString());
        ink = color(R.color.cp_ink); brand = color(R.color.cp_brand);
        mint = color(R.color.cp_mint); white = color(R.color.cp_surface); line = color(R.color.cp_line);
        setId(R.id.live_participation);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusableInTouchMode(true);
        body = column();
        body.setPadding(dp(12), dp(8), dp(12), dp(10));
        addView(body, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout titleCard = row();
        titleCard.setPadding(dp(16), dp(10), dp(12), dp(10));
        titleCard.setBackground(shape(white, 14, line));
        lessonTitle = label("", 20, true);
        lessonTitle.setMaxLines(2);
        titleCard.addView(lessonTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView live = label("●  수업 중", 12, true);
        live.setTextColor(brand);
        live.setPadding(dp(10), dp(5), dp(10), dp(5));
        live.setBackground(shape(mint, 20, 0));
        titleCard.addView(live);
        body.addView(titleCard, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout outlineCard = column();
        outlineCard.setPadding(dp(12), dp(14), dp(12), dp(8));
        outlineCard.setBackground(shape(white, 12, 0));
        TextView heading = label("오늘의 수업 목차", 20, true);
        heading.setPadding(0, 0, 0, dp(10));
        outlineCard.addView(heading);
        outlineScroll = new ScrollView(context);
        outlineScroll.setId(R.id.live_outline_scroll);
        outlineScroll.setFillViewport(false);
        outlineScroll.setVerticalScrollBarEnabled(false);
        outline = column();
        outlineScroll.addView(outline, new ScrollView.LayoutParams(-1, -2));
        outlineCard.addView(outlineScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams outlineParams = new LinearLayout.LayoutParams(-1, 0, 1);
        outlineParams.topMargin = dp(12);
        body.addView(outlineCard, outlineParams);

        reactionBox = column();
        reactionBox.setId(R.id.live_reactions);
        reactionBox.setPadding(dp(8), dp(10), dp(8), dp(10));
        reactionBox.setBackground(shape(mint, 14, white));
        reactionHeading = label("빠른 반응", 17, true);
        reactionHeading.setPadding(dp(4), 0, 0, dp(10));
        reactionBox.addView(reactionHeading);
        String[] labels = {"이해했음", "이해가 안 돼요", "너무 빨라요", "용어가 어려워요", "계산을 놓쳤어요"};
        int[] icons = {R.drawable.ic_live_thumb_up, R.drawable.ic_cp_confused, R.drawable.ic_live_speed,
                R.drawable.ic_live_menu_book, R.drawable.ic_live_calculate};
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout buttons = row();
            int start = rowIndex == 0 ? 0 : 3, end = rowIndex == 0 ? 3 : 5;
            for (int i = start; i < end; i++) {
                final int index = i;
                TextView button = label(labels[i], 12, true);
                button.setId(new int[]{R.id.live_understood, R.id.live_confused, R.id.live_speed,
                        R.id.live_term, R.id.live_calculation}[i]);
                button.setGravity(Gravity.CENTER);
                button.setPadding(dp(6), dp(4), dp(6), dp(4));
                button.setMinHeight(dp(52));
                android.graphics.drawable.Drawable icon = ContextCompat.getDrawable(context, icons[i]).mutate();
                icon.setTint(brand);
                icon.setBounds(0, 0, dp(22), dp(22));
                button.setCompoundDrawablesRelative(icon, null, null, null);
                button.setCompoundDrawablePadding(dp(4));
                button.setBackground(touchShape(i == 0 ? 0xFFD1ECD9 : white, 10, i == 0 ? 0xFFA4D6B5 : line));
                button.setTextColor(i == 0 ? brand : ink);
                button.setOnClickListener(v -> {
                    if (index == 0) message("이해했음 반응은 준비 중이에요.");
                    else listener.reaction(index - 1);
                });
                button.setContentDescription(labels[i] + (i == 0 ? " · 준비 중" : ""));
                ViewCompat.setAccessibilityDelegate(button, new androidx.core.view.AccessibilityDelegateCompat() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host,
                            androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName("android.widget.Button");
                    }
                });
                reactions[i] = button;
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                if (i > start) params.leftMargin = dp(6);
                buttons.addView(button, params);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            if (rowIndex > 0) params.topMargin = dp(8);
            reactionBox.addView(buttons, params);
        }
        LinearLayout.LayoutParams reactionParams = new LinearLayout.LayoutParams(-1, -2);
        reactionParams.topMargin = dp(14);
        body.addView(reactionBox, reactionParams);

        LinearLayout composer = column();
        composer.setId(R.id.live_composer);
        topicCaption = label("질문할 대주제를 선택하세요", 12, true);
        topicCaption.setMaxLines(2);
        topicCaption.setPadding(dp(3), dp(12), 0, dp(8));
        composer.addView(topicCaption);
        LinearLayout inputRow = row();
        topicButton = label("–", 20, true);
        topicButton.setId(R.id.live_topic_button);
        topicButton.setGravity(Gravity.CENTER);
        topicButton.setBackground(touchShape(white, 26, line));
        topicButton.setElevation(dp(1));
        android.graphics.drawable.Drawable arrow = ContextCompat.getDrawable(context, R.drawable.ic_live_expand_more).mutate();
        arrow.setTint(ink); arrow.setBounds(0, 0, dp(16), dp(16));
        topicButton.setCompoundDrawablesRelative(null, null, arrow, null);
        topicButton.setPadding(dp(11), 0, dp(7), 0);
        topicButton.setOnClickListener(v -> { if (expanded) collapse(); else expand(); });
        inputRow.addView(topicButton, new LinearLayout.LayoutParams(dp(50), dp(50)));
        input = new androidx.appcompat.widget.AppCompatEditText(context);
        input.setId(R.id.live_question_input);
        input.setTextSize(15);
        input.setTextColor(ink);
        input.setHintTextColor(color(R.color.cp_muted));
        input.setHint("질문을 입력하세요");
        input.setContentDescription("질문 내용");
        input.setBackground(shape(white, 12, 0xFFCFD9D3));
        input.setPadding(dp(12), dp(10), dp(10), dp(10));
        input.setMinHeight(dp(48));
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        input.setSaveEnabled(false); // Account/session scoped storage owns restoration, not view hierarchy IDs.
        input.setText(preferences.getString(this.scope + "text", ""));
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!changingText) persist();
                updateEnabled();
            }
            public void afterTextChanged(Editable s) {}
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEND) return false;
            submit(); return true;
        });
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, -2, 1);
        inputParams.setMargins(dp(10), 0, dp(10), 0);
        inputRow.addView(input, inputParams);
        send = new ImageButton(context);
        send.setId(R.id.live_send);
        send.setImageResource(R.drawable.ic_live_near_me);
        send.setImageTintList(ColorStateList.valueOf(white));
        send.setPadding(dp(12), dp(12), dp(12), dp(12));
        send.setBackground(touchShape(brand, 24, 0));
        send.setContentDescription("질문 전송");
        send.setOnClickListener(v -> submit());
        inputRow.addView(send, new LinearLayout.LayoutParams(dp(48), dp(48)));
        composer.addView(inputRow);
        body.addView(composer);

        picker = new ScrollView(context);
        picker.setId(R.id.live_topic_picker);
        picker.setBackground(shape(white, 25, line));
        picker.setElevation(dp(8));
        picker.setClipToOutline(true);
        picker.setVerticalScrollBarEnabled(false);
        picker.setVisibility(GONE);
        addView(picker, new FrameLayout.LayoutParams(dp(50), dp(50)));
        addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (expanded && (r - l != or - ol || b - t != ob - ot)) collapse();
        });
        persist();
    }

    public void bind(String title, List<Topic> next, String activeId, boolean questionsOpen,
            boolean canReact, boolean[] sent) {
        lessonTitle.setText(title);
        accepting = questionsOpen;
        String previousCurrent = currentId;
        currentId = activeId == null ? "" : activeId;
        topics = new ArrayList<>(next);
        roots.clear();
        for (Topic topic : topics) if (topic.parentId.isEmpty()) roots.add(topic);
        // Follow progress until the student explicitly selects a question destination.
        boolean explicit = preferences.getBoolean(scope + "chosen", false);
        if (root(selectedId) == null || (!explicit && input.getText().length() == 0)) {
            String target = majorFor(currentId);
            selectedId = !target.isEmpty() ? target : roots.isEmpty() ? "" : roots.get(0).id;
        }
        StringBuilder key = new StringBuilder(currentId);
        for (Topic topic : topics) key.append('|').append(topic.id).append('|').append(topic.title)
                .append('|').append(topic.parentId).append('|').append(topic.completed);
        if (!key.toString().equals(outlineKey)) {
            outlineKey = key.toString();
            rebuildOutline(!currentId.equals(previousCurrent));
            collapse();
        }
        for (int i = 1; i < reactions.length; i++) {
            boolean received = sent != null && sent.length >= i && sent[i - 1];
            reactions[i].setEnabled(canReact && !received);
            reactions[i].setSelected(received);
            reactions[i].setAlpha(canReact || received ? 1f : .48f);
            reactions[i].setBackground(touchShape(received ? 0xFFD1ECD9 : white, 10, received ? 0xFFA4D6B5 : line));
            ViewCompat.setStateDescription(reactions[i], received ? "반응 전달됨" : canReact ? "선택 가능" : "접수 마감");
        }
        reactionHeading.setText(questionsOpen ? "빠른 반응" : "빠른 반응 · 접수 마감");
        updateTopic();
        updateEnabled();
    }

    private void rebuildOutline(boolean revealCurrent) {
        int oldY = outlineScroll.getScrollY();
        outline.removeAllViews();
        View active = null;
        for (int i = 0; i < roots.size(); i++) {
            Topic root = roots.get(i);
            List<Topic> children = new ArrayList<>();
            for (Topic topic : topics) if (root.id.equals(topic.parentId)) children.add(topic);
            boolean done = root.completed || (!children.isEmpty() && children.stream().allMatch(t -> t.completed));
            if (i > 0) {
                View rule = new View(getContext()); rule.setBackgroundColor(line);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
                params.setMargins(0, dp(12), 0, dp(10)); outline.addView(rule, params);
            }
            View rootRow = outlineRow(root, (i + 1) + ". " + root.title, false, done);
            outline.addView(rootRow);
            if (root.id.equals(currentId)) active = rootRow;
            for (int j = 0; j < children.size(); j++) {
                Topic child = children.get(j);
                View childRow = outlineRow(child, (i + 1) + "." + (j + 1) + " " + child.title, true, child.completed);
                outline.addView(childRow);
                if (child.id.equals(currentId)) active = childRow;
            }
        }
        if (roots.isEmpty()) outline.addView(label("수업 목차를 불러오고 있어요.", 14, false));
        final View activeRow = active;
        outlineScroll.post(() -> {
            if (revealCurrent && activeRow != null) {
                int bottom = activeRow.getBottom(), height = outlineScroll.getHeight();
                if (activeRow.getTop() < oldY || bottom > oldY + height)
                    outlineScroll.scrollTo(0, Math.max(0, activeRow.getTop() - height / 3));
                else outlineScroll.scrollTo(0, oldY);
            } else outlineScroll.scrollTo(0, oldY);
        });
    }

    private View outlineRow(Topic topic, String title, boolean child, boolean done) {
        boolean active = topic.id.equals(currentId);
        LinearLayout row = row();
        row.setMinimumHeight(dp(child ? 34 : 40));
        row.setPadding(dp(5), dp(5), dp(5), dp(5));
        if (active) row.setBackground(shape(0xFFD9F0E1, 6, 0));
        View rail = new View(getContext());
        rail.setBackground(shape(active ? color(R.color.cp_accent) : child ? line : white, 3, 0));
        LinearLayout.LayoutParams railParams = new LinearLayout.LayoutParams(dp(active ? 4 : 1), active ? -1 : dp(child ? 22 : 1));
        railParams.rightMargin = dp(child ? 12 : 7);
        row.addView(rail, railParams);
        if (done && !active) {
            ImageView check = new ImageView(getContext());
            check.setImageResource(R.drawable.ic_live_check);
            check.setImageTintList(ColorStateList.valueOf(white));
            check.setPadding(dp(2), dp(2), dp(2), dp(2));
            check.setBackground(shape(0xFF32A45B, 12, 0));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(17), dp(17));
            cp.rightMargin = dp(8); row.addView(check, cp);
        }
        TextView text = label(title, child ? 15 : 17, !child || active);
        text.setMaxLines(3);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (active) {
            TextView badge = label("진행 중", 11, true);
            badge.setTextColor(brand);
            badge.setPadding(dp(7), dp(4), dp(7), dp(4));
            badge.setBackground(shape(mint, 16, 0));
            row.addView(badge);
        }
        row.setContentDescription(title + (active ? ", 진행 중" : done ? ", 완료" : ", 예정"));
        ViewCompat.setScreenReaderFocusable(row, true);
        row.setTag(topic.id);
        return row;
    }

    private void updateTopic() {
        Topic topic = root(selectedId);
        int index = topic == null ? 0 : roots.indexOf(topic) + 1;
        topicButton.setText(index == 0 ? "–" : String.valueOf(index));
        topicButton.setContentDescription(topic == null ? "대주제 선택" : "질문할 대주제 " + index + ", " + topic.title + ", 펼치기");
        topicCaption.setText(topic == null ? "질문할 대주제를 선택하세요" : "질문할 대주제 · " + index + ". " + topic.title);
        persist();
    }

    public boolean isExpanded() { return expanded; }
    public void onExpandedChanged(Runnable action) { expandedChanged = action; }
    public void expand() {
        if (roots.isEmpty() || sending || expanded) return;
        int[] anchor = new int[2], origin = new int[2];
        topicButton.getLocationInWindow(anchor); getLocationInWindow(origin);
        int bottom = anchor[1] - origin[1] + topicButton.getHeight();
        int available = Math.max(dp(50), bottom - dp(8));
        int target = Math.min(dp(roots.size() * 48 + 20), available);
        LinearLayout numbers = column();
        numbers.setPadding(dp(4), dp(10), dp(4), dp(10));
        for (int i = 0; i < roots.size(); i++) {
            Topic topic = roots.get(i);
            TextView number = label(String.valueOf(i + 1), 21, true);
            number.setGravity(Gravity.CENTER);
            boolean chosen = topic.id.equals(selectedId);
            number.setTextColor(chosen ? white : ink);
            number.setBackground(touchShape(chosen ? brand : white, 24, 0));
            number.setContentDescription("대주제 " + (i + 1) + ", " + topic.title + (chosen ? ", 선택됨" : ""));
            number.setOnClickListener(v -> {
                selectedId = topic.id;
                preferences.edit().putBoolean(scope + "chosen", true).apply();
                updateTopic(); collapse(); topicButton.requestFocus();
            });
            numbers.addView(number, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        picker.removeAllViews(); picker.addView(numbers);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.BOTTOM | Gravity.LEFT);
        params.leftMargin = anchor[0] - origin[0];
        params.bottomMargin = getHeight() - bottom;
        picker.setLayoutParams(params);
        topicButton.setVisibility(INVISIBLE);
        picker.setVisibility(VISIBLE); picker.bringToFront();
        expanded = true; expandedChanged.run();
        animation = ValueAnimator.ofInt(dp(50), target);
        animation.setDuration(200);
        animation.addUpdateListener(value -> {
            ViewGroup.LayoutParams p = picker.getLayoutParams();
            p.height = (int) value.getAnimatedValue(); picker.setLayoutParams(p);
        });
        animation.start();
        picker.post(() -> picker.scrollTo(0, Math.max(0, roots.indexOf(root(selectedId)) * dp(48) - target / 2)));
        picker.announceForAccessibility("질문할 대주제를 선택하세요");
    }

    public void collapse() {
        if (!expanded) return;
        if (animation != null) animation.cancel();
        expanded = false;
        picker.setVisibility(GONE); topicButton.setVisibility(VISIBLE);
        expandedChanged.run();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (expanded && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            Rect bounds = new Rect(); picker.getHitRect(bounds);
            if (!bounds.contains((int) event.getX(), (int) event.getY())) collapse();
        }
        return super.dispatchTouchEvent(event);
    }

    private void submit() {
        if (sending || !accepting || root(selectedId) == null || input.getText().toString().trim().isEmpty()) return;
        handler.removeCallbacks(autosave);
        collapse(); sending = true; updateEnabled();
        final String requestId = draftId;
        listener.send(requestId, selectedId, input.getText().toString(), (success, message) -> {
            boolean ownsDraft = requestId.equals(preferences.getString(scope + "id", ""));
            if (success && ownsDraft) {
                preferences.edit().remove(scope + "text").remove(scope + "id").remove(scope + "chosen").apply();
            }
            sending = false;
            if (success && requestId.equals(draftId)) {
                draftId = UUID.randomUUID().toString();
                savedDraft = "";
                changingText = true; input.setText(""); changingText = false;
                if (ownsDraft && isAttachedToWindow()) persist();
            }
            updateEnabled();
            if (isAttachedToWindow()) message(message);
        });
    }

    /** A queued question may be observed after rotation before its original callback returns. */
    public void acknowledge(String id) {
        if (!id.equals(draftId) || sending) return;
        draftId = UUID.randomUUID().toString();
        savedDraft = "";
        changingText = true; input.setText(""); changingText = false;
        preferences.edit().remove(scope + "chosen").apply();
        persist(); updateEnabled();
    }

    private void updateEnabled() {
        if (send == null) return;
        send.setEnabled(accepting && !sending && root(selectedId) != null && input.getText().toString().trim().length() > 0);
        send.setAlpha(accepting && !sending ? 1f : .45f);
        input.setEnabled(!sending);
        topicButton.setEnabled(!sending && !roots.isEmpty());
        ViewCompat.setStateDescription(send, sending ? "전송 준비 중" : accepting ? "질문 전송" : "질문 접수 마감");
    }

    private void persist() {
        preferences.edit().putString(scope + "id", draftId).putString(scope + "topic", selectedId)
                .putString(scope + "text", input.getText().toString()).apply();
        handler.removeCallbacks(autosave);
        if (!sending) handler.postDelayed(autosave, 900);
    }
    public void flushDraft() {
        handler.removeCallbacks(autosave);
        if (sending || root(selectedId) == null) return;
        String text = input.getText().toString();
        String key = draftId + "|" + selectedId + "|" + text;
        if (key.equals(savedDraft) || (text.trim().isEmpty() && !savedDraft.startsWith(draftId + "|"))) return;
        savedDraft = key;
        listener.saveDraft(draftId, selectedId, text);
    }
    private Topic root(String id) { for (Topic topic : roots) if (topic.id.equals(id)) return topic; return null; }
    private String majorFor(String id) {
        for (Topic topic : topics) if (topic.id.equals(id)) return topic.parentId.isEmpty() ? topic.id : topic.parentId;
        return "";
    }
    private void message(String text) { Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show(); announceForAccessibility(text); }
    private int color(int id) { return ContextCompat.getColor(getContext(), id); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(getContext()); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(getContext()); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView label(String text, int sp, boolean bold) {
        TextView v = new TextView(getContext()); v.setText(text); v.setTextSize(sp); v.setTextColor(ink);
        v.setFontFeatureSettings("kern"); v.setIncludeFontPadding(false);
        if (bold) v.setTypeface(null, Typeface.BOLD);
        return v;
    }
    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private RippleDrawable touchShape(int fill, int radius, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(color(R.color.cp_control_pressed)), shape(fill, radius, stroke), shape(white, radius, 0));
    }
    @Override protected void onDetachedFromWindow() {
        flushDraft(); collapse(); handler.removeCallbacks(autosave); super.onDetachedFromWindow();
    }

    public void mount(FragmentShellBinding shell) {
        shell.demoBanner.setVisibility(GONE); shell.scroll.setVisibility(GONE);
        shell.dock.setVisibility(GONE); shell.bottomNav.setVisibility(GONE);
        shell.screenTitle.setText("수업 참여"); shell.roleLabel.setText("학생");
        ((View) shell.screenTitle.getParent()).setBackgroundColor(white);
        if (getParent() == null) shell.getRoot().addView(this, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    public void unmount(FragmentShellBinding shell) {
        input.clearFocus();
        android.view.inputmethod.InputMethodManager ime = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
        collapse(); shell.getRoot().removeView(this);
        ((View) shell.screenTitle.getParent()).setBackground(null);
        shell.scroll.setVisibility(VISIBLE); shell.demoBanner.setVisibility(VISIBLE);
    }
}
