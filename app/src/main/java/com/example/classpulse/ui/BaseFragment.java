package com.example.classpulse.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.example.classpulse.MainActivity;
import com.example.classpulse.data.LessonRepository;
import com.example.classpulse.databinding.FragmentShellBinding;
import com.example.classpulse.domain.Models;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.function.Consumer;

public abstract class BaseFragment extends Fragment {
    protected AppViewModel vm;
    protected Ui ui;
    protected LinearLayout content, dock;
    protected FragmentShellBinding shell;
    private Runnable renderAction;
    private boolean observing;
    private ScreenContext screen;
    private long viewGeneration;
    private String viewRole;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        vm = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
        ui = new Ui(requireContext());
        screen = ScreenContext.from(getArguments());
    }

    protected MainActivity host() {
        return (MainActivity) requireActivity();
    }

    protected final ScreenContext screen() {
        return screen;
    }

    protected final void navigate(String target) {
        host().navigate(target, screen);
    }

    protected final void navigate(String target, ScreenContext destination) {
        host().navigate(target, destination);
    }

    protected final void finishTo(String target, ScreenContext destination) {
        host().replaceCurrent(target, destination);
    }

    protected final long viewGeneration() {
        return viewGeneration;
    }

    protected final boolean isCurrentView(long generation) {
        return generation == viewGeneration && isScreenActive();
    }

    protected final boolean isScreenActive() {
        return isAdded()
                && getView() != null
                && vm.role().equals(viewRole)
                && getViewLifecycleOwner()
                        .getLifecycle()
                        .getCurrentState()
                        .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                && !getParentFragmentManager().isStateSaved()
                && getParentFragmentManager().findFragmentById(com.example.classpulse.R.id.main)
                        == this;
    }

    protected void setup(View root, String title, boolean showBack) {
        viewGeneration++;
        viewRole = vm.role();
        shell = FragmentShellBinding.bind(root);
        content = shell.content;
        dock = shell.dock;
        shell.screenTitle.setText(title);
        shell.backButton.setVisibility(showBack ? View.VISIBLE : View.INVISIBLE);
        shell.backButton.setOnClickListener(v -> host().back());
        shell.roleLabel.setText(vm.teacher() ? "교수자" : "STUDENT".equals(vm.role()) ? "학생" : "");
        String n = getClass().getSimpleName();
        boolean nav =
                n.equals("HomeFragment")
                        || n.equals("RecordsFragment")
                        || n.equals("SettingsFragment")
                        || n.equals("ReviewFragment");
        shell.bottomNav.setVisibility(nav ? View.VISIBLE : View.GONE);
        shell.navHome.setOnClickListener(v -> host().navigateRoot("home"));
        shell.navRecords.setText(vm.teacher() ? "질문 관리" : "내 기록");
        shell.navRecords.setOnClickListener(v -> {
            if (vm.teacher() && lesson() == null) {
                showError("먼저 관리할 수업을 선택해 주세요.");
                return;
            }
            navigate(vm.teacher() ? "questions" : "records");
        });
        shell.navSettings.setOnClickListener(v -> navigate("settings"));
    }

    protected Models.Lesson lesson() {
        return vm.lesson(screen.lessonId);
    }

    protected void clear() {
        content.removeAllViews();
        dock.removeAllViews();
        dock.setVisibility(View.GONE);
    }

    protected void observe(Runnable render) {
        renderAction = render;
        if (getView() != null) attach();
    }

    private void attach() {
        if (observing || renderAction == null) return;
        observing = true;
        vm.lessons.observe(
                getViewLifecycleOwner(),
                ignored -> {
                    if (renderAction != null) renderAction.run();
                });
        renderAction.run();
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle state) {
        super.onViewCreated(v, state);
        attach();
    }

    @Override
    public void onDestroyView() {
        viewGeneration++;
        observing = false;
        renderAction = null;
        super.onDestroyView();
        shell = null;
        content = null;
        dock = null;
    }

    protected void showError(String message) {
        if (isAdded())
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("확인해 주세요")
                    .setMessage(message)
                    .setPositiveButton("확인", null)
                    .show();
    }

    protected void notConnected(String feature) {
        if (isAdded())
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(feature + " 연결 준비")
                    .setMessage(
                            "현재는 이 기기의 로컬 시연 모드입니다. "
                                    + feature
                                    + " 서비스는 아직 연결하지 않았습니다. 입력한 내용은 보존되며 직접 작성하는 기능을 사용할 수 있습니다.")
                    .setPositiveButton("확인", null)
                    .show();
    }

    protected <T> LessonRepository.Callback<T> callback(Consumer<T> success) {
        final long generation = viewGeneration;
        return new LessonRepository.Callback<T>() {
            @Override
            public void onSuccess(T value) {
                if (isCurrentView(generation)) success.accept(value);
            }

            @Override
            public void onError(String message) {
                if (isCurrentView(generation)) showError(message);
            }
        };
    }
}
