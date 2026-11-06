package com.example.classpulse.data;

import android.content.Context;
import androidx.room.Room;
import androidx.test.platform.app.InstrumentationRegistry;

/** Shares the existing repository injection seam with account UI tests. */
public final class LocalDatabaseScope implements AutoCloseable {
    private LessonRepository repository, previous;

    public LocalDatabaseScope(Context context) {
        AppDatabase database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> {
                    repository = LessonRepository.forTests(database);
                    previous = LessonRepository.swapForTests(repository);
                });
    }

    @Override
    public void close() {
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> LessonRepository.swapForTests(previous));
        repository.closeForTests();
    }
}
