package com.example.classpulse;

import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import com.example.classpulse.databinding.ActivityMainBinding;
import com.example.classpulse.ui.AppViewModel;
import com.example.classpulse.auth.AuthFailure;
import com.example.classpulse.auth.AuthServices;
import com.example.classpulse.auth.AuthViewModel;
import com.example.classpulse.auth.GoogleCredentials;
import com.example.classpulse.ui.ScreenContext;
import com.example.classpulse.ui.common.*;
import com.example.classpulse.ui.student.*;
import com.example.classpulse.ui.teacher.*;
import com.example.classpulse.ui.online.*;
import com.example.classpulse.online.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class MainActivity extends AppCompatActivity {
    private AppViewModel vm;
    private AuthViewModel auth;
    private GoogleCredentials credentials;
    private CancellationSignal credentialCancellation;
    private long credentialRequest = -1;
    private String onlineUiUid = "";
    private boolean loginDestinationPending;
    private AlertDialog exitDialog;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        EdgeToEdge.enable(this);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(),
                (v, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                    v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                    return insets;
                });
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), binding.getRoot());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);
        vm = new ViewModelProvider(this).get(AppViewModel.class);
        auth = new ViewModelProvider(this).get(AuthViewModel.class);
        credentials = AuthServices.credentials(getApplicationContext());
        onlineUiUid = state == null ? "" : state.getString("online_uid", "");
        loginDestinationPending = state != null && state.getBoolean("login_destination_pending");
        if (state == null) {
            captureInvite(getIntent());
            if (auth.current().user != null && !OnlineInvites.token(vm.pendingInvite()).isEmpty())
                openSignedInDestination();
            else navigateRoot(startRoute());
        }
        auth.state().observe(this, ignored -> reconcileAccountRoute());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                backToPreviousScreenOrConfirmExit();
            }
        });
    }

    private String startRoute() {
        return "NONE".equals(vm.role())
                ? (auth.current().user == null ? "welcome" : "online_home")
                : vm.pendingInvite().isEmpty() ? "home" : "join";
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureInvite(intent);
        if (auth.current().user != null && !OnlineInvites.token(vm.pendingInvite()).isEmpty()) {
            openSignedInDestination();
            return;
        }
        if ("NONE".equals(vm.role()))
            navigateRoot(auth.current().user == null ? "welcome" : "online_home");
        else navigate("join");
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        reconcileAccountRoute();
    }

    private void reconcileAccountRoute() {
        if (auth != null)
            OnlineServices.identityChanged(
                    auth.current().user == null ? "" : auth.current().user.uid);
        if (auth == null
                || vm == null
                || !getLifecycle()
                        .getCurrentState()
                        .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                || getSupportFragmentManager().isStateSaved()) return;
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.main);
        boolean onlineScreen = current != null
                && current.getTag() != null
                && current.getTag().startsWith("online_");
        boolean accountScreen = current != null && "account".equals(current.getTag());
        boolean accountChanged = !onlineUiUid.isEmpty()
                && (auth.current().user == null
                        || !onlineUiUid.equals(auth.current().user.uid));
        if ((onlineScreen && auth.current().user == null)
                || ((onlineScreen || accountScreen) && accountChanged)) {
            // Tab history belongs to one UID and must never survive an account change.
            navigateRoot("account");
            onlineUiUid = "";
            return;
        }
        if (current != null
                && auth.current().user != null
                && !auth.current().busy()
                && (("welcome".equals(current.getTag()) && "NONE".equals(vm.role()))
                        || ("account".equals(current.getTag()) && loginDestinationPending)))
            openSignedInDestination();
    }

    private void openSignedInDestination() {
        loginDestinationPending = false;
        openOnlineRoot("online_home");
        if (!OnlineInvites.token(vm.pendingInvite()).isEmpty())
            show("online_join", true, ScreenContext.empty());
    }

    public void openGoogleAccount() {
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.main);
        boolean fromWelcome = "NONE".equals(vm.role())
                && current != null && "welcome".equals(current.getTag());
        vm.setRole("NONE");
        if (fromWelcome) show("account", true, ScreenContext.empty());
        else navigateRoot("account");
        if (auth.current().user == null) startGoogleLogin();
    }

    public void startGoogleLogin() {
        final long request = auth.beginCredentialRequest();
        if (request < 0) return;
        loginDestinationPending = true;
        credentialRequest = request;
        credentialCancellation = new CancellationSignal();
        final AuthViewModel ongoing = auth;
        credentials.request(
                this,
                getString(R.string.default_web_client_id),
                credentialCancellation,
                new GoogleCredentials.Callback() {
                    @Override
                    public void onToken(String token) {
                        ongoing.credentialReceived(request, token);
                    }

                    @Override
                    public void onError(AuthFailure failure) {
                        ongoing.credentialFailed(request, failure);
                    }
                });
    }

    public void signOutGoogle() {
        loginDestinationPending = false;
        OnlineServices.close();
        vm.setRole("NONE");
        auth.signOut(credentials);
        navigateRoot("account");
        onlineUiUid = "";
    }

    public OnlineRepository onlineRepository() {
        if (auth.current().user == null
                || !onlineUiUid.isEmpty() && !onlineUiUid.equals(auth.current().user.uid))
            throw new IllegalStateException("Account changed");
        onlineUiUid = auth.current().user.uid;
        return OnlineServices.open(getApplicationContext(), auth.current().user.uid);
    }

    public void openOnline(String target) {
        if (auth.current().user == null) {
            openGoogleAccount();
            return;
        }
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.main);
        boolean sameAccountHistory = "NONE".equals(vm.role())
                && (onlineUiUid.isEmpty() || onlineUiUid.equals(auth.current().user.uid))
                && current != null && current.getTag() != null
                && (current.getTag().startsWith("online_") || "account".equals(current.getTag()));
        onlineUiUid = auth.current().user.uid;
        vm.setRole("NONE");
        if (sameAccountHistory) {
            if (target.equals(current.getTag())
                    && ScreenContext.from(current.getArguments()).lessonId.isEmpty()) return;
            show(target, true, ScreenContext.empty());
        } else navigateRoot(target);
    }

    /** Authentication and cold invites establish a new, account-owned history root. */
    private void openOnlineRoot(String target) {
        if (auth.current().user == null) {
            openGoogleAccount();
            return;
        }
        onlineUiUid = auth.current().user.uid;
        vm.setRole("NONE");
        navigateRoot(target);
    }

    public void openOnlineTab(String target) {
        if (!"online_home".equals(target)
                && !"online_records".equals(target)
                && !"account".equals(target)) return;
        Fragment current = getSupportFragmentManager().findFragmentById(R.id.main);
        if (current != null
                && target.equals(current.getTag())
                && ScreenContext.from(current.getArguments()).lessonId.isEmpty()) return;
        openOnline(target);
    }

    public String pendingInvite() {
        return vm.pendingInvite();
    }

    public void clearPendingInvite() {
        vm.setPendingInvite("");
    }

    public void openOnlineJoined(String id) {
        navigateRoot("online_home");
        show("online_session", true, ScreenContext.empty().forLesson(id));
    }

    public void enterDemo(String role) {
        if (auth.current().busy()) return;
        OnlineServices.close();
        vm.setRole(role);
        navigateRoot("STUDENT".equals(role) && !vm.pendingInvite().isEmpty() ? "join" : "home");
    }

    @Override
    protected void onDestroy() {
        if (exitDialog != null) exitDialog.dismiss();
        if (credentialCancellation != null) credentialCancellation.cancel();
        if (auth != null && credentialRequest >= 0)
            auth.abandonCredentialRequest(credentialRequest);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("online_uid", onlineUiUid);
        state.putBoolean("login_destination_pending", loginDestinationPending);
        super.onSaveInstanceState(state);
    }

    private void captureInvite(Intent intent) {
        if (intent != null && intent.getData() != null)
            vm.setPendingInvite(intent.getData().toString());
    }

    public void navigate(String target) {
        navigate(target, ScreenContext.empty());
    }

    public void navigate(String target, ScreenContext context) {
        if ("home".equals(target) || "welcome".equals(target)) {
            navigateRoot(target);
            return;
        }
        show(target, true, context);
    }

    public void navigateRoot(String target) {
        if (isFinishing() || getSupportFragmentManager().isStateSaved()) return;
        getSupportFragmentManager()
                .popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        show(target, false, ScreenContext.empty());
    }

    /** Complete a form without returning to the submitted form on Back. */
    public void replaceCurrent(String target, ScreenContext context) {
        if (isFinishing() || getSupportFragmentManager().isStateSaved()) return;
        FragmentManager manager = getSupportFragmentManager();
        if (manager.getBackStackEntryCount() > 0) manager.popBackStackImmediate();
        else {
            show("home", false, ScreenContext.empty());
            manager.executePendingTransactions();
        }
        Fragment previous = manager.findFragmentById(R.id.main);
        if (previous != null && target.equals(previous.getTag())) {
            ScreenContext previousContext = ScreenContext.from(previous.getArguments());
            boolean sameLesson = context.lessonId.equals(previousContext.lessonId);
            boolean sameItem =
                    !("question_detail".equals(target) || "quiz_result".equals(target))
                            || ("question_detail".equals(target)
                                    ? context.questionId.equals(previousContext.questionId)
                                    : context.quizId.equals(previousContext.quizId));
            if (sameLesson && sameItem) return;
        }
        show(target, true, context);
    }

    /** Joining from a cold deep link also needs a real Home parent. */
    public void openJoinedLesson(String target, ScreenContext context) {
        navigateRoot("home");
        show(target, true, context);
    }

    private void show(String target, boolean stack, ScreenContext context) {
        if (isFinishing() || getSupportFragmentManager().isStateSaved()) return;
        if (target.startsWith("online_") && auth.current().user == null) {
            target = "account";
            stack = false;
            context = ScreenContext.empty();
        }
        if ("NONE".equals(vm.role())
                && !"welcome".equals(target)
                && !"account".equals(target)
                && !target.startsWith("online_")) {
            target = auth.current().user == null ? "welcome" : "account";
            stack = false;
            context = ScreenContext.empty();
        }
        Fragment f;
        Bundle args = new Bundle();
        switch (target) {
            case "welcome":
                f = new WelcomeFragment();
                break;
            case "account":
                f = new AccountFragment();
                break;
            case "online_home":
                f = new OnlineHomeFragment();
                break;
            case "online_records":
                f = new OnlineRecordsFragment();
                break;
            case "online_edit":
                f = new OnlineEditorFragment();
                break;
            case "online_join":
                f = new OnlineJoinFragment();
                break;
            case "online_invite":
                f = new OnlineInviteFragment();
                break;
            case "online_session":
                f = new OnlineSessionFragment();
                break;
            case "online_question_edit":
                f = new OnlineQuestionEditorFragment();
                break;
            case "online_question":
                f = new OnlineQuestionDetailFragment();
                break;
            case "online_material":
                f = new OnlineMaterialsFragment();
                break;
            case "online_quizzes":
                f = new OnlineQuizzesFragment();
                break;
            case "home":
                f = new HomeFragment();
                break;
            case "create":
                f = new CreateLessonFragment();
                break;
            case "edit_lesson":
                f = new CreateLessonFragment();
                args.putBoolean("edit", true);
                break;
            case "invite":
                f = new InviteFragment();
                break;
            case "join":
                f = new JoinFragment();
                break;
            case "confirm_join":
                f = new ConfirmJoinFragment();
                break;
            case "live":
                f = new LiveLessonFragment();
                break;
            case "questions":
                f = new TeacherQuestionsFragment();
                break;
            case "analysis":
                f = new AnalyticsFragment();
                break;
            case "question_edit":
                f = new QuestionEditorFragment();
                break;
            case "question_detail":
                f = new QuestionDetailFragment();
                break;
            case "records":
                f = new RecordsFragment();
                break;
            case "review":
                f = new ReviewFragment();
                break;
            case "material_edit":
                f = new MaterialEditorFragment();
                break;
            case "transcript_summary":
                f = new TranscriptFragment();
                args.putInt("material_mode", 1);
                break;
            case "transcript_supplement":
                f = new TranscriptFragment();
                args.putInt("material_mode", 2);
                break;
            case "transcript":
                f = new TranscriptFragment();
                break;
            case "quiz_list":
                f = new QuizListFragment();
                break;
            case "quiz_edit":
                f = new QuizEditorFragment();
                break;
            case "quiz_take":
                f = new QuizTakeFragment();
                break;
            case "quiz_result":
                f = new QuizResultFragment();
                break;
            case "settings":
                f = new SettingsFragment();
                break;
            default:
                new MaterialAlertDialogBuilder(this)
                        .setMessage("이 화면을 열 수 없습니다: " + target)
                        .setPositiveButton("확인", null)
                        .show();
                return;
        }
        context.writeTo(args);
        f.setArguments(args);
        androidx.fragment.app.FragmentTransaction transaction =
                getSupportFragmentManager()
                        .beginTransaction()
                        .setReorderingAllowed(true)
                        .replace(R.id.main, f, target);
        if (stack) transaction.addToBackStack(target);
        transaction.commit();
    }

    private void backToPreviousScreenOrConfirmExit() {
        if (isFinishing() || isDestroyed()) return;
        FragmentManager manager = getSupportFragmentManager();
        if (manager.isStateSaved()) return;
        manager.executePendingTransactions();
        if (manager.popBackStackImmediate()) return;
        if (exitDialog != null && exitDialog.isShowing()) return;
        exitDialog = new MaterialAlertDialogBuilder(this)
                .setTitle("앱 종료")
                .setMessage("종료하시겠습니까?")
                .setNegativeButton("아니요", null)
                .setPositiveButton("예", (dialog, which) -> finish())
                .create();
        exitDialog.setOnDismissListener(dialog -> exitDialog = null);
        exitDialog.show();
    }

    public void back() {
        getOnBackPressedDispatcher().onBackPressed();
    }
}
