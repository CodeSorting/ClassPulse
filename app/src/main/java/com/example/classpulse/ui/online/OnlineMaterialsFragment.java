package com.example.classpulse.ui.online;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.example.classpulse.online.LectureRecording;
import com.example.classpulse.online.LectureUploadWorker;
import com.example.classpulse.online.OnlineJson;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class OnlineMaterialsFragment extends OnlineLearningPage {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = () -> reload(true);
    private int partIndex;
    private String recordingNotice = "", uploadId = "";
    private LiveData<List<WorkInfo>> uploadWork;
    private WorkInfo upload;
    private final ActivityResultLauncher<String> microphone = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted && shell != null) checkNotification();
                else if (shell != null) error("수업 녹음을 시작하려면 마이크 권한이 필요해요.");
            });
    private final ActivityResultLauncher<String> notification = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (shell != null) beginRecording();
            });
    private final ActivityResultLauncher<String[]> recordingPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::importRecording);

    @Override
    protected String title() {
        return "녹음 및 복습 자료";
    }

    @Override
    protected String loadAction() {
        return "getLectureMaterial";
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) partIndex = state.getInt("partIndex");
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        out.putInt("partIndex", partIndex);
        super.onSaveInstanceState(out);
    }

    @Override
    public void onStart() {
        super.onStart();
        LectureRecording.events().removeObservers(getViewLifecycleOwner());
        LectureRecording.events().observe(getViewLifecycleOwner(), event -> {
            recordingNotice = repo != null && event != null && repo.uid.equals(event.uid)
                    && screen.lessonId.equals(event.sessionId) ? event.message : "";
            render();
        });
    }

    @Override
    public void onStop() {
        main.removeCallbacks(refresh);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        uploadWork = null;
        uploadId = "";
        upload = null;
        super.onDestroyView();
    }

    @Override
    protected void render() {
        if (shell == null || repo == null) return;
        clear();
        JSONObject lesson = lesson();
        if (lesson.optString("id").isEmpty()) {
            ui.note(shell.content, "수업을 불러오고 있어요.");
            return;
        }
        boolean teacher = owner();
        shell.roleLabel.setText(teacher ? "교수자" : "학생");
        ui.heading(shell.content, lesson.optString("title"), "수업 원문 · 대주제별 요약 · 보충 설명");
        if (teacher) showRecording(lesson.optString("status"));
        ui.button(shell.content, loading ? "자료 불러오는 중…" : "자료 상태 새로고침", false, this::reload)
                .setEnabled(!loading && !sending);
        if (sending) ui.note(shell.content, "서버 요청을 처리하고 있어요.");
        if (content == null) return;
        if (teacher) showJob(content.optJSONObject("job"));
        JSONObject material = content.optJSONObject("material");
        JSONArray parts = content.optJSONArray("parts");
        if (material == null || parts == null || parts.length() == 0) {
            ui.note(shell.content, teacher ? "녹음을 변환하면 이 화면에서 원문을 검토할 수 있어요."
                    : "교수가 공개한 원문이 아직 없어요.");
        } else {
            showTranscript(teacher, material, parts);
        }
        showNotes(teacher, content.optJSONArray("notes"));
        ui.button(shell.content, "복습 퀴즈", false, () -> open("online_quizzes"));
    }

    private void showRecording(String state) {
        boolean recording = LectureRecording.isRecording(repo.uid, screen.lessonId);
        LectureRecording.Saved saved = LectureRecording.saved(requireContext(), repo.uid, screen.lessonId);
        ui.section(shell.content, "수업 녹음", recording ? "녹음 중" : saved == null ? "저장된 녹음 없음" : "기기에 저장된 녹음 있음");
        if (!recordingNotice.isEmpty()) ui.note(shell.content, recordingNotice);
        if (recording) {
            ui.button(shell.content, "녹음 종료 및 저장", true, () -> LectureRecording.stop(requireContext()));
            return;
        }
        if ("LIVE".equals(state)) {
            ui.button(shell.content, saved == null ? "수업 녹음 시작" : "새 녹음 시작", true, this::confirmRecording);
        }
        if (!"ENDED".equals(state)) return;
        if (saved != null) observeUpload(saved.id);
        boolean importing = LectureRecording.isImporting(repo.uid, screen.lessonId);
        boolean uploading = upload != null && !upload.getState().isFinished();
        JSONObject job = content == null ? null : content.optJSONObject("job");
        boolean converting = job != null && active(job);
        ui.button(shell.content, importing ? "테스트 녹음 불러오는 중…" : "테스트 녹음 파일 선택 (.m4a)",
                false, () -> recordingPicker.launch(new String[]{"audio/*"}))
                .setEnabled(!importing && !uploading && !converting && !sending);
        if (importing || saved == null) return;
        if (saved.startedAt == 0) ui.note(shell.content, "테스트 녹음의 원문은 대주제 미지정으로 표시돼요.");
        boolean ready = upload != null && upload.getState() == WorkInfo.State.SUCCEEDED;
        if (!ready) {
            String label = "녹음 업로드";
            if (uploading) label = "업로드 대기 또는 진행 중 · " + upload.getProgress().getInt("percent", 0) + "%";
            else if (upload != null) label = "업로드 실패 · 다시 시도";
            ui.button(shell.content, label, true, () -> LectureUploadWorker.enqueue(
                    requireContext(), repo.uid, screen.lessonId, saved.id)).setEnabled(!uploading && !sending);
        } else {
            ui.note(shell.content, "녹음 업로드 완료");
            String jobState = job == null ? "" : job.optString("state");
            boolean done = job != null && "READY".equals(jobState) && saved.id.equals(job.optString("recordingId"));
            ui.button(shell.content, done ? "STT 변환 완료" : "STT 변환 요청", true,
                    () -> request("startLectureStt", value -> reload(),
                            "recordingId", saved.id, "startedAt", saved.startedAt))
                    .setEnabled(!converting && !done && !sending);
        }
    }

    private void importRecording(Uri source) {
        if (source == null || shell == null || !owner() || !"ENDED".equals(lesson().optString("status"))) return;
        LectureRecording.importM4a(requireContext(), repo.uid, screen.lessonId, source);
    }

    private void observeUpload(String id) {
        if (id.equals(uploadId)) return;
        if (uploadWork != null) uploadWork.removeObservers(getViewLifecycleOwner());
        uploadId = id;
        upload = null;
        uploadWork = WorkManager.getInstance(requireContext())
                .getWorkInfosForUniqueWorkLiveData(LectureUploadWorker.workName(id));
        uploadWork.observe(getViewLifecycleOwner(), work -> {
            upload = null;
            for (WorkInfo info : work) {
                upload = info;
                if (!info.getState().isFinished() || info.getState() == WorkInfo.State.SUCCEEDED) break;
            }
            render();
        });
    }

    private void confirmRecording() {
        new MaterialAlertDialogBuilder(requireContext()).setTitle("수업 녹음을 시작할까요?")
                .setMessage("녹음 사실을 수업 참여자에게 안내한 뒤 시작해 주세요. 새 녹음은 수업 자료에 연결할 녹음으로 선택됩니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("녹음 시작", (dialog, button) -> {
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) checkNotification();
                    else microphone.launch(Manifest.permission.RECORD_AUDIO);
                }).show();
    }

    private void checkNotification() {
        android.content.SharedPreferences preferences =
                requireContext().getSharedPreferences("lecture_recordings", android.content.Context.MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED &&
                !preferences.getBoolean("askedNotifications", false)) {
            preferences.edit().putBoolean("askedNotifications", true).apply();
            notification.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else beginRecording();
    }

    private void beginRecording() {
        if (!owner() || !"LIVE".equals(lesson().optString("status"))) return;
        if (LectureRecording.hasActiveRecording()) {
            error("이미 녹음 중인 수업을 먼저 종료해 주세요.");
            return;
        }
        try {
            LectureRecording.start(requireContext(), repo.uid, screen.lessonId);
        } catch (RuntimeException error) {
            error("화면을 연 상태에서 마이크 권한을 확인한 뒤 다시 시작해 주세요.");
        }
    }

    private boolean active(JSONObject job) {
        String state = job.optString("state");
        return ("SUBMITTING".equals(state) || "PROCESSING".equals(state))
                && job.optLong("expiresAt") > System.currentTimeMillis();
    }

    private void showJob(JSONObject job) {
        main.removeCallbacks(refresh);
        if (job == null) return;
        if (active(job)) {
            ui.note(shell.content, "CLOVA Speech에서 원문으로 변환하고 있어요.");
            main.postDelayed(refresh, 5000);
        } else if ("READY".equals(job.optString("state"))) {
            ui.note(shell.content, "변환 완료 · 전체 원문을 확인해 주세요.");
        } else {
            String reason = job.optString("error");
            ui.note(shell.content, "변환 실패 또는 대기 시간 초과 · 녹음과 서버 설정을 확인하고 다시 요청해 주세요."
                    + (reason.isEmpty() ? "" : " (" + reason + ")"));
        }
    }

    private void showTranscript(boolean teacher, JSONObject material, JSONArray parts) {
        partIndex = Math.min(partIndex, parts.length() - 1);
        JSONObject part = parts.optJSONObject(partIndex);
        if (part == null) return;
        String topicId = part.optString("topicId");
        String label = topicId.isEmpty() ? "대주제 미지정" : topicTitle(topicId);
        ui.section(shell.content, teacher ? "전체 원문 검토" : "공개 전체 원문",
                (partIndex + 1) + " / " + parts.length() + " · " + label + " · "
                        + elapsed(part.optLong("startMs")) + "–" + elapsed(part.optLong("endMs")));
        ui.text(shell.content, part.optString("text"));
        ui.button(shell.content, "이전 원문 구간", false, () -> { partIndex--; render(); })
                .setEnabled(partIndex > 0);
        ui.button(shell.content, "다음 원문 구간", false, () -> { partIndex++; render(); })
                .setEnabled(partIndex + 1 < parts.length());
        ui.button(shell.content, "대주제로 이동", false, () -> chooseTopic(true, id -> {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject candidate = parts.optJSONObject(i);
                if (candidate != null && id.equals(candidate.optString("topicId"))) {
                    partIndex = i;
                    render();
                    return;
                }
            }
            error("이 대주제에 연결된 원문이 없어요.");
        }));
        if (!teacher) return;
        ui.button(shell.content, "이 구간 수정", false, () -> editPart(material, part)).setEnabled(!sending);
        JSONObject published = content.optJSONObject("published");
        if (published != null) ui.note(shell.content, "학생 공개 버전 " + published.optInt("version"));
        ui.button(shell.content, "전체 원문 공개", true, () -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle("검토한 전체 원문을 공개할까요?")
                .setMessage("참여 학생이 종료된 수업에서 이 버전의 원문을 볼 수 있어요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("공개", (dialog, button) -> request("publishLecture", value -> reload(),
                        "operationId", OnlineJson.id(), "baseRevision", material.optLong("revision")))
                .show()).setEnabled(!sending);
    }

    private String elapsed(long millis) {
        long seconds = millis / 1000;
        return String.format(java.util.Locale.KOREA, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void editPart(JSONObject material, JSONObject part) {
        LinearLayout fields = editorContent();
        String[] topic = { part.optString("topicId") };
        MaterialButton button = ui.button(fields,
                topic[0].isEmpty() ? "대주제 미지정" : topicTitle(topic[0]), false, () -> {});
        button.setOnClickListener(view -> chooseTopic(true, id -> {
            topic[0] = id;
            button.setText(id.isEmpty() ? "대주제 미지정" : topicTitle(id));
        }));
        EditText text = ui.field(fields, "원문", part.optString("text"), true);
        text.setFilters(new InputFilter[] { new InputFilter.LengthFilter(8000) });
        AlertDialog dialog = editor("원문 구간 수정", fields).setPositiveButton("저장", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
                saveEditor(dialog, "saveLecturePart", this::reload, "operationId", OnlineJson.id(),
                        "baseRevision", material.optLong("revision"), "partId", part.optString("id"),
                        "topicId", topic[0], "text", text.getText().toString())));
        dialog.show();
    }

    private void showNotes(boolean teacher, JSONArray notes) {
        ui.section(shell.content, "대주제별 요약과 보충 설명", teacher ? "AI 초안을 검토한 뒤 학생에게 공개할 수 있어요." : "");
        if (teacher) {
            ui.button(shell.content, "대주제 자료 편집", false, () -> chooseTopic(false, id -> editNote(id, notes)))
                    .setEnabled(!sending);
        }
        if (notes == null || notes.length() == 0) ui.note(shell.content, "등록된 대주제 자료가 없어요.");
        else for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note == null) continue;
            String topic = topicTitle(note.optString("topicId"));
            ui.button(shell.content, topic + (teacher ? " 자료 편집" : " 요약·보충 설명"), false,
                    () -> {
                        if (teacher) editNote(note.optString("topicId"), notes);
                        else new MaterialAlertDialogBuilder(requireContext()).setTitle(topic)
                                .setMessage("요약\n" + note.optString("summary") + "\n\n보충 설명\n" + note.optString("supplement"))
                                .setPositiveButton("닫기", null).show();
                    }).setEnabled(!sending);
        }
    }

    private void editNote(String topicId, JSONArray notes) {
        JSONObject note = find(notes, "topicId", topicId);
        LinearLayout fields = editorContent();
        EditText summary = ui.field(fields, "요약", note.optString("summary"), true);
        MaterialButton summarize = ui.button(fields, "이 대주제 AI 요약", false, () -> {});
        summarize.setOnClickListener(view -> aiText(view, summary, "mode", "summary", "topicId", topicId));
        EditText supplement = ui.field(fields, "보충 설명", note.optString("supplement"), true);
        MaterialButton supplementAi = ui.button(fields, "AI 보충 설명 초안", false, () -> {});
        supplementAi.setOnClickListener(view -> aiText(view, supplement, "mode", "supplement", "topicId", topicId));
        InputFilter[] limit = { new InputFilter.LengthFilter(6000) };
        summary.setFilters(limit);
        supplement.setFilters(limit);
        AlertDialog dialog = editor(topicTitle(topicId), fields).setPositiveButton("공개", null)
                .setNeutralButton("초안 저장", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
                    saveNote(dialog, note, topicId, summary, supplement, false));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
                    saveNote(dialog, note, topicId, summary, supplement, true));
        });
        dialog.show();
    }

    private void saveNote(AlertDialog dialog, JSONObject note, String topicId, EditText summary, EditText supplement, boolean publish) {
        saveEditor(dialog, "saveLearningNote", this::reload, "operationId", OnlineJson.id(), "topicId", topicId,
                "baseRevision", note.optLong("revision"), "summary", summary.getText().toString(),
                "supplement", supplement.getText().toString(), "publish", publish);
    }
}
