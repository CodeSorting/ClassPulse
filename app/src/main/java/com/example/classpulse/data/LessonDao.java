package com.example.classpulse.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import androidx.room.Update;
import androidx.room.Upsert;
import java.util.List;
import com.example.classpulse.data.LocalEntities.*;

@Dao
public interface LessonDao {
    @Transaction
    @Query("SELECT * FROM lessons ORDER BY createdAt DESC")
    LiveData<List<LessonGraph>> observeGraphs();

    @Transaction
    @Query("SELECT * FROM lessons ORDER BY createdAt DESC")
    List<LessonGraph> graphs();

    @Query("SELECT COUNT(*) FROM lessons")
    int countLessons();

    @Query("SELECT * FROM lessons WHERE id=:id")
    LessonRow lesson(String id);

    @Query("SELECT * FROM lessons WHERE code=:code LIMIT 1")
    LessonRow lessonByCode(String code);

    @Insert
    void insertLesson(LessonRow row);

    @Update
    void updateLesson(LessonRow row);

    @Query("SELECT * FROM topics WHERE sessionId=:id ORDER BY position ASC")
    List<TopicRow> topics(String id);

    @Query("SELECT * FROM topics WHERE id=:id")
    TopicRow topic(String id);

    @Insert
    void insertTopics(List<TopicRow> rows);

    @Update
    void updateTopic(TopicRow row);

    @Delete
    void deleteTopic(TopicRow row);

    @Query(
            "SELECT (SELECT COUNT(*) FROM questions WHERE topicId=:id) + (SELECT COUNT(*) FROM marks WHERE topicId=:id) + (SELECT COUNT(*) FROM reactions WHERE topicId=:id) + (SELECT COUNT(*) FROM topic_visits WHERE topicId=:id) + (SELECT COUNT(*) FROM quizzes WHERE topicId=:id)")
    int topicReferenceCount(String id);

    @Query("SELECT * FROM topic_visits WHERE id=:id")
    VisitRow visit(String id);

    @Insert
    void insertVisit(VisitRow row);

    @Update
    void updateVisit(VisitRow row);

    @Query("SELECT * FROM memberships WHERE sessionId=:sessionId AND studentId=:uid LIMIT 1")
    MemberRow member(String sessionId, String uid);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertMember(MemberRow row);

    @Query("SELECT * FROM questions WHERE id=:id")
    QuestionRow question(String id);

    @Upsert
    void saveQuestion(QuestionRow row);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertReaction(ReactionRow row);

    @Upsert
    void saveMark(MarkRow row);

    @Query("DELETE FROM marks WHERE id=:id")
    void deleteMark(String id);

    @Query("SELECT * FROM materials WHERE sessionId=:id")
    MaterialRow material(String id);

    @Upsert
    void saveMaterial(MaterialRow row);

    @Upsert
    void saveDownload(DownloadRow row);

    @Query("DELETE FROM downloads WHERE studentId=:uid")
    void clearDownloads(String uid);

    @Query("SELECT * FROM quizzes WHERE id=:id")
    QuizRow quiz(String id);

    @Upsert
    void saveQuiz(QuizRow row);

    @Query("SELECT * FROM attempts WHERE studentId=:uid AND quizId=:quizId LIMIT 1")
    AttemptRow attempt(String uid, String quizId);

    @Query("SELECT COUNT(*) FROM attempts WHERE quizId=:quizId")
    int attemptCount(String quizId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertAttempt(AttemptRow row);
}
