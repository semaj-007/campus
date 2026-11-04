package com.example.campus.services;
import androidx.room.*;
@Dao
public interface CachedSnapshotDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) void put(CachedSnapshot snapshot);
    @Query("SELECT * FROM cached_snapshots WHERE owner=:owner AND path=:path LIMIT 1") CachedSnapshot get(String owner,String path);
}
