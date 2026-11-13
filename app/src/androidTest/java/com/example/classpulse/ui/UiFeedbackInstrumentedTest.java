package com.example.classpulse.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.classpulse.R;
import com.example.classpulse.databinding.FragmentShellBinding;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual drawable output must react to press and disabled states on common controls. */
@RunWith(AndroidJUnit4.class)
public class UiFeedbackInstrumentedTest {
    @Test
    public void commonButtonsAndTabsVisiblyChangeWhilePressed() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext();
            Ui ui = new Ui(context);
            LinearLayout parent = new LinearLayout(context);
            parent.setOrientation(LinearLayout.VERTICAL);
            MaterialButton primary = ui.button(parent, "수업 시작", true, () -> {});
            MaterialButton secondary = ui.button(parent, "다시 불러오기", false, () -> {});
            MaterialButton danger = ui.button(parent, "수업 종료", false, () -> {});
            MaterialButton google = ui.googleButton(parent, "Google 로그인", () -> {});
            MaterialButton choice = ui.choice(parent, "첫 번째 보기", true, () -> {});
            ui.pair(parent, "이해가 안 돼요", () -> {}, "너무 빨라요", () -> {});
            LinearLayout pair = (LinearLayout) parent.getChildAt(parent.getChildCount() - 1);
            FragmentShellBinding shell = FragmentShellBinding.inflate(LayoutInflater.from(context));
            shell.navHome.setSelected(true);

            for (MaterialButton button : Arrays.asList(primary, secondary, danger, google, choice,
                    (MaterialButton) pair.getChildAt(0), (MaterialButton) pair.getChildAt(1),
                    shell.navHome, shell.navRecords)) {
                Bitmap idle = render(button);
                button.setPressed(true);
                Bitmap pressed = render(button);
                assertFalse(button.getText() + " has no visible press feedback", idle.sameAs(pressed));
                button.setPressed(false);
                button.setEnabled(false);
                Bitmap disabled = render(button);
                assertFalse(button.getText() + " has no disabled appearance", idle.sameAs(disabled));
                button.setEnabled(true);
                idle.recycle();
                pressed.recycle();
                disabled.recycle();
            }
        });
    }

    @Test
    public void cardsTopicsAndBackKeepNativeRippleAndClickActions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext();
            Ui ui = new Ui(context);
            LinearLayout parent = new LinearLayout(context);
            AtomicInteger clicks = new AtomicInteger();
            MaterialCardView card = ui.card(parent, "통계학개론", "수업 보기", "진행 중", clicks::incrementAndGet);
            ui.topic(parent, "베이즈 정리", "진행 중", true, clicks::incrementAndGet);
            MaterialCardView topic = (MaterialCardView) parent.getChildAt(1);
            int[] pressedState = {android.R.attr.state_enabled, android.R.attr.state_pressed};
            for (MaterialCardView control : Arrays.asList(card, topic)) {
                assertNotNull(control.getRippleColor());
                assertTrue("Card ripple is invisible", Color.alpha(
                        control.getRippleColor().getColorForState(pressedState, Color.TRANSPARENT)) > 0);
                assertTrue(control.isClickable());
                assertTrue(control.isFocusable());
                control.performClick();
            }
            FragmentShellBinding shell = FragmentShellBinding.inflate(LayoutInflater.from(context));
            shell.backButton.setOnClickListener(view -> clicks.incrementAndGet());
            assertTrue(shell.backButton.getBackground() instanceof RippleDrawable);
            ColorStateList backRipple = ContextCompat.getColorStateList(context, R.color.cp_control_ripple);
            assertNotNull(backRipple);
            assertTrue("Back ripple color is invisible", Color.alpha(
                    backRipple.getColorForState(pressedState, Color.TRANSPARENT)) > 0);
            shell.backButton.performClick();
            assertEquals("Feedback must preserve each click action", 3, clicks.get());
        });
    }

    private static Context themedContext() {
        return new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.Theme_ClassPulse);
    }

    private static Bitmap render(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        view.jumpDrawablesToCurrentState();
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }
}
