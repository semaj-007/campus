package com.example.campus.services;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface OfflineHandoffDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) void insert(OfflineHandoff handoff);
    @Query("SELECT * FROM offline_handoffs WHERE state = 'PENDING' ORDER BY completedAt") List<OfflineHandoff> pending();
    @Query("SELECT COUNT(*) FROM offline_handoffs WHERE state = 'PENDING'") int pendingCount();
    @Query("SELECT COUNT(*) FROM offline_handoffs WHERE state = 'PENDING' AND courierId=:courierId") int pendingCountForCourier(String courierId);
    @Query("UPDATE offline_handoffs SET state = :state, failureReason = :reason WHERE localTransactionId = :id") void update(String id, String state, String reason);
}
