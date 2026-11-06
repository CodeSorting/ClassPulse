package com.example.classpulse.data;

import androidx.room.Database;
import androidx.room.RoomDatabase;
import com.example.classpulse.data.LocalEntities.*;

@Database(
        entities = {
            LessonRow.class,
            TopicRow.class,
            VisitRow.class,
            MemberRow.class,
            QuestionRow.class,
            ReactionRow.class,
            MarkRow.class,
            MaterialRow.class,
            DownloadRow.class,
            QuizRow.class,
            AttemptRow.class
        },
        version = 1,
        exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    public abstract LessonDao lessonDao();
}
