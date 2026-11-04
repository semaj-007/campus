package com.example.campus.services;
import androidx.annotation.NonNull;
import androidx.room.Entity;
@Entity(tableName="cached_snapshots", primaryKeys={"owner", "path"})
public class CachedSnapshot {
    @NonNull public String owner="";
    @NonNull public String path="";
    @NonNull public String payload="";
    public long updatedAt;
}
