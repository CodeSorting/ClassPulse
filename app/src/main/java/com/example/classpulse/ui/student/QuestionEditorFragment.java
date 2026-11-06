package com.example.classpulse.ui.student;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.classpulse.R;
import com.example.classpulse.data.LessonRepository;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.example.classpulse.ui.Ui;
import java.util.UUID;

public class QuestionEditorFragment extends BaseFragment {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autosave = () -> save(false, false);
    private EditText questionInput;
    private String draftId,
            topicId,
            editorSessionId,
            editorStudentId,
            buffer = "",
            savedText = "",
            savedTopic = "";
    private boolean rendered,
            writing,
            submittingRequest,
            submitted,
            draftExists,
            explicitQueued,
            submitQueued,
            navigateQueued,
            autosaveQueued;

    @Override
    public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        if (state != null) {
            draftId = state.getString("draftId");
            topicId = state.getString("topicId");
            editorSessionId = state.getString("sessionId");
            editorStudentId = state.getString("studentId");
            buffer = state.getString("text", "");
        }
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        setup(root, "질문 작성", true);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        observe(this::render);
    }

    private void render() {
        if (rendered && questionInput != null) {
            Models.Lesson open = lesson();
            Models.Question persisted = open == null ? null : open.question(draftId);
            if (persisted == null || StudentSupport.draft(persisted)) return;
            submitted = true;
        }
        clear();
        questionInput = null;
        final Models.Lesson course = lesson();
        if (course == null) {
            ui.note(content, "수업 정보를 불러오고 있어요.");
            return;
        }
        if (!"STUDENT".equals(vm.role()) || !StudentSupport.member(course, vm.userId())) {
            ui.heading(content, "수업에 먼저 참여해 주세요", "질문은 참여한 학생이 남길 수 있어요.");
            ui.button(dock, "내 수업으로", true, () -> navigate("home"));
            return;
        }
        if (editorSessionId == null) editorSessionId = course.id;
        if (editorStudentId == null) editorStudentId = vm.userId();
        Models.Question existing = course.question(draftId != null ? draftId : screen().questionId);
        if (existing != null
                && !StudentSupport.draft(existing)
                && vm.userId().equals(existing.studentId)) {
            submitted = true;
            rendered = true;
            ui.heading(content, "이미 접수된 질문이에요", "제출한 질문의 대주제와 내용은 그대로 보관돼요.");
            ui.card(
                    content,
                    StudentSupport.safe(existing.text),
                    Ui.topicTitle(course, existing.topicId),
                    existing.published ? "답변 공개됨" : "답변 대기",
                    null);
            ui.button(
                    dock,
                    "제출한 질문 보기",
                    true,
                    () -> {
                        finishTo(
                                "question_detail",
                                screen().withQuestion(existing.id).withTopic(existing.topicId));
                    });
            ui.button(
                    dock,
                    "새 질문 작성",
                    false,
                    () -> {
                        draftId = UUID.randomUUID().toString();
                        draftExists = false;
                        submitted = false;
                        buffer = "";
                        savedText = savedTopic = "";
                        rendered = false;
                        render();
                    });
            return;
        }
        if (existing != null
                && StudentSupport.draft(existing)
                && vm.userId().equals(existing.studentId)) {
            draftExists = true;
            if (draftId == null) {
                draftId = existing.id;
                if (buffer.isEmpty()) buffer = StudentSupport.safe(existing.text);
                if (topicId == null) topicId = existing.topicId;
                savedText = StudentSupport.safe(existing.text);
                savedTopic = StudentSupport.safe(existing.topicId);
            }
        }
        if (topicId == null || course.topic(topicId) == null) topicId = screen().topicId;
        if (topicId == null || course.topic(topicId) == null) topicId = course.currentTopicId;
        if ((topicId == null || course.topic(topicId) == null) && !course.topics.isEmpty()) {
            topicId = course.topics.get(0).id;
        }
        if (topicId == null) {
            ui.note(content, "질문할 대주제가 아직 없어요.");
            return;
        }
        if (draftId == null) draftId = UUID.randomUUID().toString();
        rendered = true;
        ui.card(
                content,
                Ui.topicTitle(course, topicId),
                "질문할 대주제",
                "선택됨",
                () -> {
                    if (submittingRequest) return;
                    capture();
                    ui.chooseTopic(
                            course,
                            topicId,
                            chosenTopic -> {
                                topicId = chosenTopic.id;
                                rendered = false;
                                render();
                                scheduleSave();
                            });
                });
        ui.note(content, "완료된 대주제에도 질문할 수 있어요. 제출한 질문은 선택한 대주제에 계속 남아요.");
        questionInput = ui.field(content, "궁금한 내용", buffer, true);
        questionInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(1000)});
        TextView counter = ui.text(content, buffer.length() + " / 1,000자");
        questionInput.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        buffer = s.toString();
                        counter.setText(buffer.length() + " / 1,000자");
                        scheduleSave();
                    }

                    @Override
                    public void afterTextChanged(Editable editable) {}
                });
        ui.pair(
                content,
                "음성으로 입력",
                () -> notConnected("음성 질문 입력"),
                "AI로 문장 정리",
                () -> notConnected("AI 질문 문장 정리"));
        ui.note(content, "제출 전 초안은 나만 볼 수 있어요. 제출한 질문은 나와 교수님이 확인하고, 공개된 답변은 참여 학생이 함께 봐요.");
        if (!course.questionsOpen) ui.note(content, "이 수업의 질문 접수가 닫혀 있어요. 작성 내용은 초안으로 보관할 수 있어요.");
        ui.button(
                dock,
                "질문 제출하기",
                true,
                () -> {
                    if (!course.questionsOpen) {
                        showError("이 수업은 현재 질문을 접수하지 않아요.");
                        return;
                    }
                    save(true, true);
                });
        ui.button(dock, "초안 저장", false, () -> save(false, true));
    }

    private void capture() {
        if (questionInput != null) buffer = questionInput.getText().toString();
    }

    private void scheduleSave() {
        handler.removeCallbacks(autosave);
        handler.postDelayed(autosave, 900);
    }

    private void save(boolean submit, boolean navigate) {
        capture();
        if (submitted || topicId == null || editorSessionId == null || editorStudentId == null)
            return;
        if (submit && buffer.trim().isEmpty()) {
            showError("궁금한 내용을 입력해 주세요.");
            return;
        }
        if (!submit && buffer.trim().isEmpty() && !draftExists) return;
        if (writing) {
            if (navigate || submit) {
                explicitQueued = true;
                submitQueued |= submit;
                navigateQueued |= navigate;
            } else autosaveQueued = true;
            return;
        }
        if (!submit && !navigate && buffer.equals(savedText) && topicId.equals(savedTopic)) return;
        handler.removeCallbacks(autosave);
        writing = true;
        submittingRequest = submit;
        if (submit) {
            autosaveQueued = false;
            if (questionInput != null) questionInput.setEnabled(false);
        }
        final String requestText = buffer, requestTopic = topicId;
        final long generation = viewGeneration();
        LessonRepository.Callback<String> result =
                new LessonRepository.Callback<String>() {
                    @Override
                    public void onSuccess(String id) {
                        writing = false;
                        submittingRequest = false;
                        draftId = id;
                        draftExists = true;
                        savedText = requestText;
                        savedTopic = requestTopic;
                        submitted = submit;
                        if (navigate && isCurrentView(generation)) {
                            Toast.makeText(
                                            requireContext(),
                                            submit ? "이 기기에서 질문을 접수했어요" : "초안을 저장했어요",
                                            Toast.LENGTH_SHORT)
                                    .show();
                            finishTo(
                                    submit ? "question_detail" : "records",
                                    screen().withQuestion(id).withTopic(requestTopic));
                        } else if (explicitQueued && !submitted) {
                            boolean nextSubmit = submitQueued, nextNavigate = navigateQueued;
                            explicitQueued = submitQueued = navigateQueued = false;
                            save(nextSubmit, nextNavigate);
                        } else if (autosaveQueued && !submitted) {
                            autosaveQueued = false;
                            save(false, false);
                        } else if (!submitted
                                && isScreenActive()
                                && (!buffer.equals(savedText)
                                        || !StudentSupport.safe(topicId).equals(savedTopic))) {
                            scheduleSave();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        writing = false;
                        submittingRequest = false;
                        explicitQueued = submitQueued = navigateQueued = false;
                        if (isCurrentView(generation)) {
                            if (questionInput != null) questionInput.setEnabled(true);
                            showError(message);
                        }
                    }
                };
        if (submit)
            vm.repo.submitQuestion(
                    draftId, editorSessionId, requestTopic, editorStudentId, requestText, result);
        else
            vm.repo.saveDraft(
                    draftId, editorSessionId, requestTopic, editorStudentId, requestText, result);
    }

    @Override
    public void onPause() {
        capture();
        handler.removeCallbacks(autosave);
        if (!submitted) save(false, false);
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (submitted && getView() != null) render();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        capture();
        out.putString("draftId", draftId);
        out.putString("topicId", topicId);
        out.putString("text", buffer);
        out.putString("sessionId", editorSessionId);
        out.putString("studentId", editorStudentId);
        super.onSaveInstanceState(out);
    }

    @Override
    public void onDestroyView() {
        capture();
        handler.removeCallbacks(autosave);
        questionInput = null;
        rendered = false;
        super.onDestroyView();
    }
}
