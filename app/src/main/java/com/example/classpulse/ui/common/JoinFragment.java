package com.example.classpulse.ui.common;

import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.classpulse.R;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import java.util.List;

public final class JoinFragment extends BaseFragment {
    private EditText input;
    private boolean codeMode;
    private String code = "", link = "";

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        String p = vm.pendingInvite();
        codeMode = "CODE".equals(p);
        if (!p.isEmpty() && !codeMode) link = p;
        if (state != null) {
            codeMode = state.getBoolean("codeMode");
            code = state.getString("code", "");
            link = state.getString("link", "");
        }
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View v = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(v, "수업 참여", true);
        render();
        return v;
    }

    private void render() {
        clear();
        ui.heading(
                content,
                codeMode ? "참여 코드 4자리를 입력해요" : "공유받은 초대 링크를 열어요",
                "링크 또는 코드 중 한 가지로 수업을 확인할 수 있어요.");
        ui.pair(content, "초대 링크", () -> switchMode(false), "코드 직접 입력", () -> switchMode(true));
        input = ui.field(content, codeMode ? "참여 코드" : "수업 초대 링크", codeMode ? code : link, false);
        if (codeMode) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
        }
        ui.note(
                content,
                codeMode
                        ? "앞자리가 0인 코드도 네 자리 모두 입력해 주세요.\n시연 수업 코드: 4821"
                        : "이 기기에서 만든 초대 링크를 입력해 주세요. 실제 온라인 수업 초대는 서비스 연결 후 사용할 수 있어요.");
        ui.text(content, "수업 정보 확인만으로 참여가 완료되지는 않아요. 다음 화면에서 참여를 선택해 주세요.");
        ui.button(dock, "수업 확인하기", true, this::confirm);
    }

    private void switchMode(boolean mode) {
        remember();
        codeMode = mode;
        render();
    }

    private void remember() {
        if (input == null) return;
        if (codeMode) code = input.getText().toString().trim();
        else link = input.getText().toString().trim();
    }

    private void confirm() {
        remember();
        String sessionId = null;
        if (codeMode) {
            if (!code.matches("[0-9]{4}")) {
                input.setError("숫자 4자리를 입력해 주세요.");
                return;
            }
            for (Models.Lesson l : vm.currentLessons())
                if (code.equals(l.code)) {
                    sessionId = l.id;
                    break;
                }
        } else {
            try {
                Uri uri = Uri.parse(link);
                List<String> parts = uri.getPathSegments();
                if ("classpulse".equals(uri.getScheme())
                        && "join".equals(uri.getHost())
                        && !parts.isEmpty()) sessionId = parts.get(0);
                else if ("https".equals(uri.getScheme())
                        && "classpulse.app".equals(uri.getHost())
                        && parts.size() == 2
                        && "join".equals(parts.get(0))) sessionId = parts.get(1);
            } catch (Exception ignored) {
                sessionId = null;
            }
        }
        Models.Lesson found = null;
        for (Models.Lesson l : vm.currentLessons()) if (l.id.equals(sessionId)) found = l;
        if (found == null) {
            showError("이 기기에서 수업을 찾지 못했어요. 링크나 코드를 확인해 주세요. 현재는 같은 기기 안의 시연 수업만 참여할 수 있습니다.");
            return;
        }
        navigate("confirm_join", screen().forLesson(found.id));
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle b) {
        remember();
        b.putBoolean("codeMode", codeMode);
        b.putString("code", code);
        b.putString("link", link);
        super.onSaveInstanceState(b);
    }

    @Override
    public void onDestroyView() {
        remember();
        input = null;
        super.onDestroyView();
    }
}
