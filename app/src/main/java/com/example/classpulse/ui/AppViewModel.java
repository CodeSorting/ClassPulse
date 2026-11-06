package com.example.classpulse.ui;

import android.app.Application;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import com.example.classpulse.data.LessonRepository;
import com.example.classpulse.domain.Models;
import java.util.Collections;
import java.util.List;

public final class AppViewModel extends AndroidViewModel {
    public final LessonRepository repo;
    public final LiveData<List<Models.Lesson>> lessons;
    public final MutableLiveData<String> roleState = new MutableLiveData<>();
    private final SavedStateHandle state;
    private final SharedPreferences preferences;

    public AppViewModel(@NonNull Application app, SavedStateHandle state) {
        super(app);
        this.state = state;
        preferences = app.getSharedPreferences("local_demo", 0);
        repo = LessonRepository.get(app);
        lessons = repo.observeLessons();
        roleState.setValue(preferences.getString("role", "NONE"));
    }

    public List<Models.Lesson> currentLessons() {
        List<Models.Lesson> l = lessons.getValue();
        return l == null ? Collections.emptyList() : l;
    }

    public Models.Lesson lesson(String id) {
        for (Models.Lesson l : currentLessons()) if (l.id.equals(id)) return l;
        return null;
    }

    public String role() {
        String v = roleState.getValue();
        return v == null ? "NONE" : v;
    }

    public boolean teacher() {
        return "TEACHER".equals(role());
    }

    public String userId() {
        return teacher() ? "demo-teacher" : "STUDENT".equals(role()) ? "demo-student" : "";
    }

    public void setRole(String role) {
        preferences.edit().putString("role", role).apply();
        roleState.setValue(role);
    }

    public String pendingInvite() {
        String s = state.get("invite");
        return s == null ? "" : s;
    }

    public void setPendingInvite(String value) {
        state.set("invite", value);
    }
}
