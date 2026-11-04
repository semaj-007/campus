package com.example.campus.services;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {OfflineHandoff.class, CachedSnapshot.class}, version = 2, exportSchema = false)
public abstract class OfflineDatabase extends RoomDatabase {
    public abstract OfflineHandoffDao handoffs();
    public abstract CachedSnapshotDao snapshots();
    private static volatile OfflineDatabase instance;
    public static OfflineDatabase get(Context context) {
        if (instance == null) synchronized (OfflineDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(), OfflineDatabase.class, "campus-offline.db").addMigrations(new androidx.room.migration.Migration(1,2) {
                @Override public void migrate(@androidx.annotation.NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS cached_snapshots (owner TEXT NOT NULL, path TEXT NOT NULL, payload TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(owner,path))");
                }
            }).build();
        }
        return instance;
    }
}
