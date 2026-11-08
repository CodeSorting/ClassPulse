package com.example.classpulse.ui.common;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.example.classpulse.MainActivity;
import com.example.classpulse.R;
import com.example.classpulse.auth.AuthIdentity;
import com.example.classpulse.auth.AuthViewModel;
import com.example.classpulse.databinding.FragmentShellBinding;
import com.example.classpulse.ui.AppViewModel;
import com.example.classpulse.ui.Ui;
import com.example.classpulse.ui.online.OnlineNavigation;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Real account screen. Never reads the demo repository or treats demo data as account data. */
public final class AccountFragment extends Fragment {
    private FragmentShellBinding binding;
    private AuthViewModel auth;
    private Ui ui;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        binding = FragmentShellBinding.inflate(inflater, container, false);
        ui = new Ui(requireContext());
        auth = new ViewModelProvider(requireActivity()).get(AuthViewModel.class);
        binding.screenTitle.setText("내 Google 계정");
        binding.roleLabel.setText("계정");
        binding.bottomNav.setVisibility(View.GONE);
        binding.backButton.setVisibility(View.INVISIBLE);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        auth.state().observe(getViewLifecycleOwner(), this::render);
    }

    private MainActivity host() {
        return (MainActivity) requireActivity();
    }

    private void render(AuthViewModel.State state) {
        if (binding == null) return;
        binding.content.removeAllViews();
        binding.dock.removeAllViews();
        binding.dock.setVisibility(View.GONE);
        if (state.user == null) {
            binding.screenTitle.setText("내 Google 계정");
            binding.bottomNav.setVisibility(View.GONE);
            binding.demoBanner.setText("Google 계정으로 시작해요");
            ui.heading(binding.content, "내 계정으로\n수업을 이어가요", "Google 계정을 선택해 ClassPulse에 로그인해 주세요.");
            ui.note(binding.content, "로그인하면 내 수업과 질문을 다른 기기에서도 이어서 볼 수 있어요.");
            if (!state.message.isEmpty()) ui.note(binding.content, state.message);
            String label =
                    state.phase == AuthViewModel.Phase.CHOOSING_ACCOUNT
                            ? "Google 계정 선택 중…"
                            : state.phase == AuthViewModel.Phase.AUTHENTICATING
                                    ? "로그인 확인 중…"
                                    : state.phase == AuthViewModel.Phase.SIGNING_OUT
                                            ? "로그아웃 중…"
                                            : "Google로 로그인";
            ui.googleButton(binding.dock, label, () -> host().startGoogleLogin())
                    .setEnabled(!state.busy());
            ui.button(binding.dock, "시작 화면으로", false, () -> host().navigateRoot("welcome"))
                    .setEnabled(!state.busy());
            return;
        }
        AuthIdentity user = state.user;
        binding.screenTitle.setText("내 정보");
        OnlineNavigation.bind(binding, host(), "account");
        binding.demoBanner.setText("Google 계정 로그인됨");
        ui.heading(
                binding.content,
                user.name.isEmpty() ? "로그인했어요" : user.name + "님, 반가워요",
                "내 Google 계정으로 로그인한 상태예요.");
        ui.card(
                binding.content,
                user.name.isEmpty() ? "Google 계정" : user.name,
                user.email,
                "로그인됨",
                null);
        if (!state.message.isEmpty()) ui.note(binding.content, state.message);
        ui.button(binding.content, "내 온라인 수업", true, () -> host().openOnline("online_home"))
                .setEnabled(!state.busy());
        ui.button(
                        binding.content,
                        "코드·링크로 온라인 수업 참여",
                        false,
                        () -> host().openOnline("online_join"))
                .setEnabled(!state.busy());
        AppViewModel demo = new ViewModelProvider(requireActivity()).get(AppViewModel.class);
        if (!demo.pendingInvite().isEmpty() && !"CODE".equals(demo.pendingInvite()))
            ui.note(binding.content, "받은 초대 링크를 보관하고 있어요. 코드·링크로 온라인 수업 참여에서 수업을 확인해 주세요.");
        ui.section(binding.content, "예시 수업으로 둘러보기", "아래 시연은 이 기기에 저장된 예시 수업을 사용해요.");
        ui.button(binding.content, "교수자 시연으로 살펴보기", false, () -> host().enterDemo("TEACHER"))
                .setEnabled(!state.busy());
        ui.button(binding.content, "학생 시연으로 살펴보기", false, () -> host().enterDemo("STUDENT"))
                .setEnabled(!state.busy());
        ui.button(
                        binding.content,
                        state.busy() ? "로그아웃 중…" : "Google 계정 로그아웃",
                        false,
                        () ->
                                new MaterialAlertDialogBuilder(requireContext())
                                        .setTitle("로그아웃할까요?")
                                        .setMessage("이 앱의 Google 계정 로그인 상태를 해제합니다.")
                                        .setNegativeButton("계속 사용", null)
                                        .setPositiveButton(
                                                "로그아웃", (dialog, which) -> host().signOutGoogle())
                                        .show())
                .setEnabled(!state.busy());
    }

    @Override
    public void onDestroyView() {
        binding = null;
        ui = null;
        super.onDestroyView();
    }
}
