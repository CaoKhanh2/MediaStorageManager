package com.example.mediastoragemanager.model

data class StorageInfo(
    val label: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val percentUsed: Int
)
