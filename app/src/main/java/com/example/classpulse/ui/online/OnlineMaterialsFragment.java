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
import com.example.classpulse.online.LectureRecordingStore;
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
        switch (section()) {
            case "transcript":
                return "전체 강의 원문";
            case "summary":
                return "요약·보충 설명";
            default:
                return "녹음 및 복습 자료";
        }
    }

    /** "transcript"=원문만, "summary"=요약·보충 설명만, ""=녹음 포함 전체(교수용). */
    private String section() {
        Bundle args = getArguments();
        return args == null ? "" : args.getString("material_section", "");
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
        if (repo != null) {
            LectureRecording.saved(requireContext(), repo.uid, screen.lessonId);
            LectureRecordingStore.resume(requireContext(), repo.uid, screen.lessonId);
        }
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
        String section = section();
        // 교수자는 단계별로 나눈다: "" = 1단계 녹음·원문 검토, "summary" = 2단계 요약·보충 설명
        boolean wantTranscript = !"summary".equals(section);
        boolean wantNotes = teacher ? "summary".equals(section) : !"transcript".equals(section);
        ui.heading(shell.content, lesson.optString("title"),
                "transcript".equals(section) ? "수업 처음부터 끝까지, 구간별 전체 원문"
                        : "summary".equals(section)
                                ? (teacher ? "2단계 · 대주제별 요약과 보충 설명 작성" : "대주제별 요약과 교수 보충 설명")
                        : teacher ? "1단계 · 녹음을 원문으로 바꾸고 검토해 공개해요"
                        : "수업 원문 · 대주제별 요약 · 보충 설명");
        if (teacher && section.isEmpty()) showRecording(lesson.optString("status"));
        ui.button(shell.content, loading ? "자료 불러오는 중…" : "자료 상태 새로고침", false, this::reload)
                .setEnabled(!loading && !sending);
        if (sending) ui.note(shell.content, "서버 요청을 처리하고 있어요.");
        if (content == null) return;
        if (teacher && section.isEmpty()) showProcessing();
        if (wantTranscript) {
            JSONObject material = content.optJSONObject("material");
            JSONArray parts = content.optJSONArray("parts");
            if (material == null || parts == null || parts.length() == 0) {
                ui.note(shell.content, teacher ? "녹음을 변환하면 이 화면에서 원문을 검토할 수 있어요."
                        : "교수가 공개한 원문이 아직 없어요.");
            } else {
                showTranscript(teacher, material, parts);
            }
        }
        if (wantNotes) showNotes(teacher, content.optJSONArray("notes"));
        if (teacher) {
            // 한 화면에 다 넣지 않고, 다음 단계 화면으로 넘어가는 버튼만 둔다
            if ("summary".equals(section))
                ui.button(shell.content, "다음 단계: 복습 퀴즈 만들기", true, () -> open("online_quizzes"));
            else if (content.optJSONObject("published") != null)
                ui.button(shell.content, "다음 단계: 요약·보충 설명 작성", true, () -> open("online_summary"));
            return;
        }
        if ("transcript".equals(section))
            ui.button(shell.content, "요약·보충 설명 보기", false, () -> open("online_summary"));
        else if ("summary".equals(section))
            ui.button(shell.content, "전체 원문 보기", false, () -> open("online_transcript"));
        ui.button(shell.content, "복습 퀴즈", false, () -> open("online_quizzes"));
    }

    private void showRecording(String state) {
        boolean recording = LectureRecording.isRecording(repo.uid, screen.lessonId);
        LectureRecording.Saved saved = LectureRecording.saved(requireContext(), repo.uid, screen.lessonId);
        ui.section(shell.content, "대단원 자동 녹음", recording ? "자동 녹음 사용 중" : saved == null ? "저장된 녹음 없음" : "기기에 저장된 녹음 있음");
        if (!recordingNotice.isEmpty()) ui.note(shell.content, recordingNotice);
        if (recording) {
            ui.button(shell.content, "자동 녹음 중단 및 저장", true, () -> LectureRecording.stop(requireContext()));
            return;
        }
        if ("LIVE".equals(state)) {
            ui.button(shell.content, "대단원 자동 녹음 시작", true, this::confirmRecording);
            ui.note(shell.content, "소단원 이동은 계속 녹음하고, 대단원이 바뀔 때 파일을 나누어 자동 변환해요.");
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
        if (!LectureRecordingStore.get(requireContext(), repo.uid, screen.lessonId, saved.id).optString("topicId").isEmpty()) return;
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
                .setMessage("녹음 사실을 수업 참여자에게 안내한 뒤 시작해 주세요. 대단원이 바뀌면 녹음을 자동 업로드·STT 변환하고 다음 녹음을 이어가요. 수업 종료 시 마이크를 끄고 마지막 파일만 처리해요. 연결이 끊기면 녹음을 중단하므로 다시 켜 주세요.")
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

    private void showProcessing() {
        main.removeCallbacks(refresh);
        JSONArray jobs = content.optJSONArray("jobs");
        if (jobs == null) jobs = new JSONArray();
        java.util.Map<String, JSONObject> rows = new java.util.LinkedHashMap<>();
        for (JSONObject local : LectureRecordingStore.list(requireContext(), repo.uid, screen.lessonId)) {
            rows.put(local.optString("id"), local);
        }
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (job == null) continue;
            String id = job.optString("recordingId");
            JSONObject local = rows.get(id);
            // A local retry can precede the new server attempt; keep showing the queue in that interval.
            if (local == null || !pendingUpload(local.optString("state")) || "READY".equals(job.optString("state"))) rows.put(id, job);
        }
        boolean polling = LectureRecording.isRecording(repo.uid, screen.lessonId);
        if (!rows.isEmpty()) ui.section(shell.content, "녹음별 처리 상태", "완료한 원문은 대단원에 연결되어 누적돼요.");
        for (java.util.Map.Entry<String, JSONObject> row : rows.entrySet()) {
            JSONObject value = row.getValue();
            String state = value.optString("state");
            boolean queued = !value.has("expiresAt") && pendingUpload(state);
            boolean converting = active(value) || "PROCESSING".equals(state) && !value.has("expiresAt");
            String label = "READY".equals(state) ? "원문 변환 완료"
                    : queued ? "업로드·변환 요청 대기 또는 진행 중" : converting ? "원문 변환 중" : "처리 실패 또는 대기 시간 초과";
            String topic = value.optString("topicId");
            ui.note(shell.content, (topic.isEmpty() ? "녹음 파일" : topicTitle(topic)) + " · " + label
                    + (value.optString("error").isEmpty() ? "" : " · " + value.optString("error")));
            polling |= queued || converting;
            if (!queued && !converting && !"READY".equals(state)) {
                String id = row.getKey();
                JSONObject local = LectureRecordingStore.get(requireContext(), repo.uid, screen.lessonId, id);
                if (!local.optString("id").isEmpty()) {
                    ui.button(shell.content, "이 녹음 다시 처리", false, () -> {
                        LectureUploadWorker.enqueue(requireContext(), repo.uid, screen.lessonId, id);
                        render();
                    }).setEnabled(!sending);
                } else if (!topic.isEmpty()) {
                    ui.button(shell.content, "이 대단원 변환 다시 요청", false, () -> request("startLectureStt", v -> reload(),
                            "recordingId", id, "topicId", topic, "startedAt", value.optLong("startedAt"),
                            "endedAt", value.optLong("endedAt"))).setEnabled(!sending);
                }
            }
        }
        if (polling) main.postDelayed(refresh, 5000);
    }

    private boolean pendingUpload(String state) {
        return "SAVED".equals(state) || "QUEUED".equals(state) || "UPLOADING".equals(state) || "SUBMITTING".equals(state);
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
                .setPositiveButton("공개", (dialog, button) -> request("publishLecture", value -> {
                            reload();
                            if (shell == null) return;
                            android.widget.Toast.makeText(requireContext(),
                                    "원문을 공개했어요. 이제 대주제별 요약·보충 설명을 작성해요.",
                                    android.widget.Toast.LENGTH_SHORT).show();
                            open("online_summary");
                        },
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
        if (notes == null || notes.length() == 0) {
            ui.note(shell.content, teacher ? "등록된 대주제 자료가 없어요." : "교수님이 공개한 요약·보충 설명이 아직 없어요.");
            return;
        }
        // 목차 순서대로 보여 준다
        java.util.List<JSONObject> ordered = new java.util.ArrayList<>();
        for (int i = 0; i < notes.length(); i++) {
            JSONObject note = notes.optJSONObject(i);
            if (note != null) ordered.add(note);
        }
        java.util.Map<String, Integer> order = new java.util.HashMap<>();
        java.util.List<com.example.classpulse.online.OnlineDatabase.Document> outline = topics();
        for (int i = 0; i < outline.size(); i++) order.put(outline.get(i).id, i);
        ordered.sort((a, b) -> Integer.compare(
                order.getOrDefault(a.optString("topicId"), Integer.MAX_VALUE),
                order.getOrDefault(b.optString("topicId"), Integer.MAX_VALUE)));
        for (JSONObject note : ordered) {
            String topic = topicTitle(note.optString("topicId"));
            if (teacher) {
                ui.button(shell.content, topic + " 자료 편집", false,
                        () -> editNote(note.optString("topicId"), notes)).setEnabled(!sending);
                continue;
            }
            // 학생: 팝업 대신 화면에 바로 글로 보여 준다
            String summary = note.optString("summary").trim();
            String supplement = note.optString("supplement").trim();
            ui.section(shell.content, topic, null);
            if (!summary.isEmpty()) ui.text(shell.content, "요약\n" + summary);
            if (!supplement.isEmpty()) ui.note(shell.content, "교수 보충 설명\n" + supplement);
            if (summary.isEmpty() && supplement.isEmpty())
                ui.note(shell.content, "이 대주제의 자료가 아직 비어 있어요.");
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
