package com.movtery.zalithlauncher.feature.notice

import com.google.gson.annotations.SerializedName

class NoticeJsonObject(
    val title: Text,
    val content: Text,
    val date: String,
    val numbering: Int
) {
    class Text(
        @SerializedName(value = "en_us", alternate = ["enUS"])
        val enUS: String
    )
}