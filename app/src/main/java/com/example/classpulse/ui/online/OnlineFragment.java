package com.example.classpulse.ui.online;

import android.os.Bundle;
import android.view.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import com.example.classpulse.MainActivity;
import com.example.classpulse.databinding.FragmentShellBinding;
import com.example.classpulse.online.*;
import com.example.classpulse.ui.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONObject;
import java.util.*;
import java.util.function.Consumer;

public abstract class OnlineFragment extends Fragment {
    protected FragmentShellBinding shell;
    protected Ui ui;
    protected OnlineRepository repo;
    protected ScreenContext screen;
    protected List<OnlineDatabase.Session> sessions = Collections.emptyList();
    protected List<OnlineDatabase.Document> docs = Collections.emptyList();
    protected List<OnlineDatabase.Draft> drafts = Collections.emptyList();
    protected List<OnlineDatabase.Outbox> operations = Collections.emptyList();
    protected List<OnlineDatabase.Conflict> conflicts = Collections.emptyList();
    protected String notice = "";
    protected boolean draftsLoaded;
    private OnlineTransport.Subscription subscription;
    private boolean observing;
    private long generation;

    protected MainActivity host() {
        return (MainActivity) requireActivity();
    }

    protected abstract String title();

    protected abstract void render();

    protected String navigationTab() {
        return "";
    }

    protected boolean observesHome() {
        return false;
    }

    protected void changed() {
        render();
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle saved) {
        shell = FragmentShellBinding.inflate(inflater, parent, false);
        ui = new Ui(requireContext());
        screen = ScreenContext.from(getArguments());
        generation++;
        observing = false;
        draftsLoaded = false;
        shell.screenTitle.setText(title());
        shell.demoBanner.setText("내 계정의 온라인 수업");
        shell.roleLabel.setText("온라인");
        shell.bottomNav.setVisibility(View.GONE);
        if (!navigationTab().isEmpty()) OnlineNavigation.bind(shell, host(), navigationTab());
        shell.backButton.setVisibility(
                !navigationTab().isEmpty() && screen.lessonId.isEmpty()
                        ? View.INVISIBLE
                        : View.VISIBLE);
        shell.backButton.setOnClickListener(v -> host().back());
        return shell.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            repo = host().onlineRepository();
        } catch (Exception e) {
            // A root back-stack clear can transiently start an older Fragment. Do not re-enter
            // FragmentManager synchronously while that transaction is still executing.
            final long token = viewToken();
            if (shell != null)
                shell.getRoot()
                        .post(() -> {
                            if (current(token) && !getParentFragmentManager().isStateSaved())
                                host().navigateRoot("account");
                        });
            return;
        }
        if (!observing) {
            observing = true;
            repo.sessions()
                    .observe(
                            getViewLifecycleOwner(),
                            v -> {
                                sessions = v;
                                changed();
                            });
            repo.operations()
                    .observe(
                            getViewLifecycleOwner(),
                            v -> {
                                operations = v;
                                changed();
                            });
            repo.notice()
                    .observe(
                            getViewLifecycleOwner(),
                            v -> {
                                notice = v;
                                changed();
                            });
            if (!screen.lessonId.isEmpty()) {
                repo.documents(screen.lessonId)
                        .observe(
                                getViewLifecycleOwner(),
                                v -> {
                                    docs = v;
                                    changed();
                                });
                repo.drafts(screen.lessonId)
                        .observe(
                                getViewLifecycleOwner(),
                                v -> {
                                    drafts = v;
                                    draftsLoaded = true;
                                    changed();
                                });
                repo.conflicts(screen.lessonId)
                        .observe(
                                getViewLifecycleOwner(),
                                v -> {
                                    conflicts = v;
                                    changed();
                                });
            }
        }
        if (observesHome()) subscription = repo.watchHome();
        else if (!screen.lessonId.isEmpty()) subscription = repo.watchLesson(screen.lessonId);
        render();
    }

    @Override
    public void onStop() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        generation++;
        shell = null;
        ui = null;
        super.onDestroyView();
    }

    protected void clear() {
        if (shell == null) return;
        shell.content.removeAllViews();
        shell.dock.removeAllViews();
        shell.dock.setVisibility(View.GONE);
    }

    protected JSONObject lesson() {
        for (OnlineDatabase.Session s : sessions)
            if (s.id.equals(screen.lessonId)) return OnlineJson.object(s.json);
        return new JSONObject();
    }

    protected boolean owner() {
        return repo != null && repo.uid.equals(lesson().optString("ownerUid"));
    }

    protected List<OnlineDatabase.Document> kind(String name) {
        List<OnlineDatabase.Document> r = new ArrayList<>();
        for (OnlineDatabase.Document d : docs) if (d.kind.equals(name) && !d.deleted) r.add(d);
        if ("QUESTION".equals(name) || "PUBLIC_QNA".equals(name)) {
            String time = "QUESTION".equals(name) ? "submittedAt" : "publishedAt";
            r.sort((a, b) -> {
                int compare =
                        Long.compare(
                                OnlineJson.object(b.json).optLong(time),
                                OnlineJson.object(a.json).optLong(time));
                return compare != 0 ? compare : b.id.compareTo(a.id);
            });
        }
        return r;
    }

    protected void moreButton(String kind, String label) {
        List<OnlineDatabase.Document> list = kind(kind);
        if (list.isEmpty()) return;
        String last = list.get(list.size() - 1).id;
        ui.button(
                shell.content,
                label,
                false,
                () ->
                        repo.more(
                                screen.lessonId,
                                kind,
                                last,
                                owner(),
                                callback(v -> {
                                    if (v.optInt("count") == 0) error("마지막 항목까지 확인했어요.");
                                })));
    }

    protected JSONObject item(String kind, String id) {
        for (OnlineDatabase.Document d : docs)
            if (d.kind.equals(kind) && d.id.equals(id) && !d.deleted)
                return OnlineJson.object(d.json);
        return new JSONObject();
    }

    protected List<OnlineDatabase.Document> topics() {
        List<OnlineDatabase.Document> list = kind("TOPIC");
        list.sort(Comparator.comparingInt(x -> OnlineJson.object(x.json).optInt("position")));
        return list;
    }

    protected String topicTitle(String id) {
        return item("TOPIC", id).optString("title", "대주제 확인 중");
    }

    protected OnlineDatabase.Draft draft(String kind, String id) {
        for (OnlineDatabase.Draft d : drafts) if (d.kind.equals(kind) && d.id.equals(id)) return d;
        return null;
    }

    protected void open(String target) {
        host().navigate(target, screen);
    }

    protected void open(String target, ScreenContext context) {
        host().navigate(target, context);
    }

    protected void error(String message) {
        if (shell != null && isAdded())
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("확인해 주세요")
                    .setMessage(message)
                    .setPositiveButton("확인", null)
                    .show();
    }

    protected boolean current(long g) {
        return g == generation
                && shell != null
                && isAdded()
                && getParentFragmentManager().findFragmentById(com.example.classpulse.R.id.main)
                        == this
                && getViewLifecycleOwner()
                        .getLifecycle()
                        .getCurrentState()
                        .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
    }

    protected long viewToken() {
        return generation;
    }

    protected OnlineRepository.Callback callback(Consumer<JSONObject> success) {
        long g = generation;
        return new OnlineRepository.Callback() {
            public void success(JSONObject value) {
                if (current(g)) success.accept(value);
            }

            public void error(String reason, String message) {
                if (current(g)) OnlineFragment.this.error(message);
            }
        };
    }

    protected void showNotice() {
        if (!notice.isEmpty()) ui.note(shell.content, notice);
    }

    protected String stateLabel(String state) {
        switch (state) {
            case "READY":
                return "시작 전";
            case "LIVE":
                return "수업 중";
            case "ENDED":
                return "수업 종료";
            case "SUBMITTED":
                return "답변 대기";
            case "ANSWERED":
                return "답변 공개됨";
            case "PENDING":
                return "전송 대기";
            case "SENDING":
                return "전송 중";
            case "RETRY_WAIT":
                return "연결 후 재전송";
            case "CONFLICT":
                return "원고 확인 필요";
            case "NEEDS_CONFIRMATION":
                return "서버 접수 확인 필요";
            case "REJECTED":
                return "확인 필요";
            case "ACKED":
                return "서버 접수됨";
            default:
                return "상태 확인 중";
        }
    }

    protected void pending() {
        for (OnlineDatabase.Outbox o : operations)
            if (o.sessionId.equals(screen.lessonId)
                    && !"ACKED".equals(o.state)
                    && !"SUPERSEDED".equals(o.errorCode)) {
                ui.note(
                        shell.content,
                        o.errorMessage.isEmpty() ? stateLabel(o.state) : o.errorMessage);
            }
    }
}
