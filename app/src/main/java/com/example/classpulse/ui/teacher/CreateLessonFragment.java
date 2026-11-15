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
import com.example.classpulse.ui.TopicOutlineEditor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Classroom setup for the local demo. The outline keeps stable IDs when it is reordered. */
public final class CreateLessonFragment extends BaseFragment {
    private EditText titleInput, descriptionInput;
    private final List<Models.Topic> topics = new ArrayList<>();
    private String titleValue = "", descriptionValue = "";
    private String editingLessonId;
    private boolean saving, editing, initialized;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        View view = inflater.inflate(R.layout.fragment_shell, container, false);
        editing = getArguments() != null && getArguments().getBoolean("edit", false);
        setup(view, editing ? "수업·목차 수정" : "수업 만들기", true);
        if (state != null) restore(state);
        if (!vm.teacher()) {
            ui.note(content, "교수자 역할에서 수업을 준비할 수 있어요.");
        } else if (!editing || initialized) {
            if (topics.isEmpty()) {
                for (String title : new String[]{"조건부확률", "독립사건", "베이즈 정리", "예제 풀이"})
                    topics.add(topic(UUID.randomUUID().toString(), title, null));
            }
            render();
        } else {
            ui.note(content, "수업 정보를 불러오고 있어요.");
        }
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        if (editing && vm.teacher()) observe(this::loadLesson);
    }

    private void loadLesson() {
        if (initialized) return;
        Models.Lesson lesson = lesson();
        if (lesson == null) return;
        if (!vm.userId().equals(lesson.teacherId) || !Models.READY.equals(lesson.status)) {
            clear();
            ui.note(content, "담당 교수만 수업 시작 전에 목차를 수정할 수 있어요.");
            ui.button(dock, "수업으로", true, () -> navigate("live"));
            return;
        }
        titleValue = lesson.title;
        descriptionValue = lesson.description;
        editingLessonId = lesson.id;
        topics.clear();
        for (Models.Topic item : lesson.topics) topics.add(topic(item.id, item.title, item.parentTopicId));
        initialized = true;
        render();
    }

    private static Models.Topic topic(String id, String title, String parentId) {
        Models.Topic topic = new Models.Topic();
        topic.id = id;
        topic.title = title;
        topic.parentTopicId = parentId;
        return topic;
    }

    private void capture() {
        if (titleInput != null) titleValue = titleInput.getText().toString();
        if (descriptionInput != null) descriptionValue = descriptionInput.getText().toString();
    }

    private void render() {
        clear();
        titleInput = ui.field(content, "수업명", titleValue, false);
        descriptionInput = ui.field(content, "회차·설명", descriptionValue, false);
        titleInput.setEnabled(!saving);
        descriptionInput.setEnabled(!saving);
        new TopicOutlineEditor(content, topics, !saving, 30);
        ui.button(dock, editing ? "수정 내용 저장" : "저장하고 초대하기", true, this::save)
                .setEnabled(!saving);
    }

    private void save() {
        if (saving || !isScreenActive() || !vm.teacher()) return;
        capture();
        if (titleValue.trim().isEmpty()) {
            titleInput.setError("수업명을 입력해 주세요.");
            titleInput.requestFocus();
            return;
        }
        List<Models.Topic> outline = new ArrayList<>();
        for (Models.Topic item : topics) {
            if (item.title.trim().isEmpty()) {
                showError("빈 대주제와 소주제의 제목을 채워 주세요.");
                return;
            }
            outline.add(topic(item.id, item.title.trim(), item.parentTopicId));
        }
        saving = true;
        long generation = viewGeneration();
        render();
        if (editing) {
            vm.repo.updateLesson(
                    editingLessonId, titleValue, descriptionValue, outline,
                    new LessonRepository.Callback<Void>() {
                        @Override
                        public void onSuccess(Void ignored) { saved(generation, editingLessonId); }

                        @Override
                        public void onError(String message) { saveFailed(generation, message); }
                    });
        } else {
            vm.repo.createLessonWithOutline(
                    titleValue, descriptionValue, outline,
                    new LessonRepository.Callback<String>() {
                        @Override
                        public void onSuccess(String id) { saved(generation, id); }

                        @Override
                        public void onError(String message) { saveFailed(generation, message); }
                    });
        }
    }

    private void saved(long generation, String id) {
        saving = false;
        if (isCurrentView(generation)) finishTo("invite", screen().forLesson(id));
    }

    private void saveFailed(long generation, String message) {
        saving = false;
        if (!isCurrentView(generation)) return;
        render();
        showError(message);
    }

    private void restore(Bundle state) {
        titleValue = state.getString("lessonTitle", "");
        descriptionValue = state.getString("lessonDescription", "");
        editingLessonId = state.getString("editingLessonId");
        initialized = state.getBoolean("initialized", false);
        ArrayList<String> values = state.getStringArrayList("topics");
        ArrayList<String> ids = state.getStringArrayList("topicIds");
        ArrayList<String> parents = state.getStringArrayList("topicParents");
        if (values == null) return;
        topics.clear();
        for (int i = 0; i < values.size(); i++) {
            String id = ids != null && i < ids.size() ? ids.get(i) : "";
            String parent = parents != null && i < parents.size() ? parents.get(i) : null;
            topics.add(topic(id.isEmpty() ? UUID.randomUUID().toString() : id, values.get(i), parent));
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle state) {
        capture();
        state.putString("lessonTitle", titleValue);
        state.putString("lessonDescription", descriptionValue);
        ArrayList<String> values = new ArrayList<>(), ids = new ArrayList<>(), parents = new ArrayList<>();
        for (Models.Topic item : topics) {
            values.add(item.title);
            ids.add(item.id);
            parents.add(item.parentTopicId);
        }
        state.putStringArrayList("topics", values);
        state.putStringArrayList("topicIds", ids);
        state.putStringArrayList("topicParents", parents);
        state.putString("editingLessonId", editingLessonId);
        state.putBoolean("initialized", initialized);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onDestroyView() {
        capture();
        titleInput = null;
        descriptionInput = null;
        super.onDestroyView();
    }
}
