package com.example.classpulse.ui.teacher;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.classpulse.R;
import com.example.classpulse.data.LessonRepository;
import com.example.classpulse.domain.Models;
import com.example.classpulse.ui.BaseFragment;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A real local lesson form. Topic IDs are created by the repository, not titles. */
public final class CreateLessonFragment extends BaseFragment {
    private EditText titleInput, descriptionInput;
    private final List<EditText> topicInputs = new ArrayList<>();
    private ArrayList<String> topicValues =
            new ArrayList<>(Arrays.asList("조건부확률", "독립사건", "베이즈 정리", "예제 풀이"));
    private ArrayList<String> topicIds = new ArrayList<>(Arrays.asList("", "", "", ""));
    private String titleValue = "", descriptionValue = "";
    private String editingLessonId;
    private boolean saving, editing, initialized;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle state) {
        View root = inflater.inflate(R.layout.fragment_shell, container, false);
        editing = getArguments() != null && getArguments().getBoolean("edit", false);
        setup(root, editing ? "수업·대주제 수정" : "수업 만들기", true);
        if (state != null) {
            titleValue = state.getString("lessonTitle", "");
            descriptionValue = state.getString("lessonDescription", "");
            ArrayList<String> saved = state.getStringArrayList("topics");
            if (saved != null) topicValues = saved;
            ArrayList<String> savedIds = state.getStringArrayList("topicIds");
            if (savedIds != null) topicIds = savedIds;
            editingLessonId = state.getString("editingLessonId");
            initialized = state.getBoolean("initialized", false);
        }
        if (!editing || initialized) render();
        else {
            clear();
            ui.note(content, "수업 정보를 불러오고 있어요.");
        }
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        if (editing)
            observe(() -> {
                if (initialized) return;
                Models.Lesson lesson = lesson();
                if (lesson == null) return;
                if (!"READY".equals(lesson.status)) {
                    clear();
                    ui.note(content, "수업을 시작한 뒤에는 목차를 바꾸지 않아요. 진행 화면에서 완료한 주제를 다시 설명할 수 있어요.");
                    ui.button(dock, "수업으로", true, () -> navigate("live"));
                    return;
                }
                titleValue = lesson.title;
                descriptionValue = lesson.description;
                editingLessonId = lesson.id;
                topicValues.clear();
                topicIds.clear();
                for (Models.Topic topic : lesson.topics) {
                    topicValues.add(topic.title);
                    topicIds.add(topic.id);
                }
                initialized = true;
                render();
            });
    }

    private void capture() {
        if (titleInput != null) titleValue = titleInput.getText().toString();
        if (descriptionInput != null) descriptionValue = descriptionInput.getText().toString();
        if (!topicInputs.isEmpty()) {
            topicValues.clear();
            for (EditText input : topicInputs) topicValues.add(input.getText().toString());
        }
    }

    private void render() {
        clear();
        topicInputs.clear();
        ui.heading(
                content, editing ? "수업 정보를 수정해요" : "오늘의 수업을 준비해요", "수업 정보와 설명할 대주제의 순서를 정해 주세요.");
        titleInput = ui.field(content, "수업명", titleValue, false);
        descriptionInput = ui.field(content, "회차·설명", descriptionValue, false);
        ui.section(content, "설명할 대주제", "주제 개수는 자유롭게 정할 수 있어요.");
        for (int i = 0; i < topicValues.size(); i++) {
            final int index = i;
            topicInputs.add(ui.field(content, (i + 1) + ". 대주제", topicValues.get(i), false));
            ui.pair(
                    content,
                    "위로 이동",
                    () -> {
                        capture();
                        if (index > 0) {
                            String v = topicValues.remove(index);
                            String id = topicIds.remove(index);
                            topicValues.add(index - 1, v);
                            topicIds.add(index - 1, id);
                            render();
                        }
                    },
                    "삭제",
                    () -> {
                        capture();
                        if (topicValues.size() == 1) {
                            showError("대주제는 한 개 이상 필요해요.");
                            return;
                        }
                        topicValues.remove(index);
                        topicIds.remove(index);
                        render();
                    });
        }
        ui.button(
                content,
                "+ 대주제 추가",
                false,
                () -> {
                    capture();
                    topicValues.add("");
                    topicIds.add("");
                    render();
                });
        ui.note(content, "수업 중 ‘완료하고 다음 주제’로 진행해요. 끝난 대주제에도 질문을 남길 수 있어요.");
        MaterialButton submit =
                ui.button(dock, editing ? "수정 내용 저장" : "저장하고 초대하기", true, () -> save());
        submit.setEnabled(!saving);
    }

    private void save() {
        if (saving) return;
        capture();
        if (titleValue.trim().isEmpty()) {
            titleInput.setError("수업명을 입력해 주세요.");
            titleInput.requestFocus();
            return;
        }
        ArrayList<String> topics = new ArrayList<>();
        for (String value : topicValues) {
            if (value.trim().isEmpty()) {
                showError("빈 대주제 제목을 채워 주세요.");
                return;
            }
            topics.add(value.trim());
        }
        saving = true;
        final long generation = viewGeneration();
        render();
        if (editing) {
            ArrayList<Models.Topic> values = new ArrayList<>();
            for (int i = 0; i < topics.size(); i++) {
                Models.Topic t = new Models.Topic();
                t.id = i < topicIds.size() ? topicIds.get(i) : "";
                t.title = topics.get(i);
                t.position = i;
                t.sessionId = editingLessonId;
                values.add(t);
            }
            vm.repo.updateLesson(
                    editingLessonId,
                    titleValue.trim(),
                    descriptionValue.trim(),
                    values,
                    new LessonRepository.Callback<Void>() {
                        @Override
                        public void onSuccess(Void value) {
                            saving = false;
                            if (isCurrentView(generation)) finishTo("invite", screen());
                        }

                        @Override
                        public void onError(String message) {
                            saving = false;
                            if (isCurrentView(generation)) {
                                showError(message);
                                render();
                            }
                        }
                    });
            return;
        }
        vm.repo.createLesson(
                titleValue.trim(),
                descriptionValue.trim(),
                topics,
                new LessonRepository.Callback<String>() {
                    @Override
                    public void onSuccess(String id) {
                        saving = false;
                        if (!isCurrentView(generation)) return;
                        finishTo("invite", screen().forLesson(id));
                    }

                    @Override
                    public void onError(String message) {
                        saving = false;
                        if (!isCurrentView(generation)) return;
                        showError(message);
                        render();
                    }
                });
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        capture();
        state.putString("lessonTitle", titleValue);
        state.putString("lessonDescription", descriptionValue);
        state.putStringArrayList("topics", topicValues);
        state.putStringArrayList("topicIds", topicIds);
        state.putString("editingLessonId", editingLessonId);
        state.putBoolean("initialized", initialized);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onDestroyView() {
        capture();
        titleInput = null;
        descriptionInput = null;
        topicInputs.clear();
        super.onDestroyView();
    }
}
