package com.movtery.zalithlauncher.utils.platform

import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE

object MemoryUtils {
    @Volatile
    private var cachedTotalMemory: Long = -1L
    private val reusableMemInfo = ActivityManager.MemoryInfo()

    private fun updateMemoryInfo(context: Context): ActivityManager.MemoryInfo {
        val activityManager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        synchronized(reusableMemInfo) {
            activityManager.getMemoryInfo(reusableMemInfo)
            if (cachedTotalMemory <= 0L) {
                cachedTotalMemory = reusableMemInfo.totalMem
            }
            return reusableMemInfo
        }
    }

    @JvmStatic
    fun getTotalDeviceMemory(context: Context): Long {
        if (cachedTotalMemory > 0L) return cachedTotalMemory
        return updateMemoryInfo(context).totalMem
    }

    @JvmStatic
    fun getUsedDeviceMemory(context: Context): Long {
        val info = updateMemoryInfo(context)
        return info.totalMem - info.availMem
    }

    @JvmStatic
    fun getFreeDeviceMemory(context: Context): Long {
        return updateMemoryInfo(context).availMem
    }
}
