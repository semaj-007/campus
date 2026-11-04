package com.example.campus.services;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "offline_handoffs")
public class OfflineHandoff {
    @PrimaryKey @NonNull public String localTransactionId = "";
    @NonNull public String deliveryId = "";
    @NonNull public String scannedQrHash = "";
    @NonNull public String courierId = "";
    public long completedAt;
    @NonNull public String state = "PENDING";
    @NonNull public String failureReason = "";
}
