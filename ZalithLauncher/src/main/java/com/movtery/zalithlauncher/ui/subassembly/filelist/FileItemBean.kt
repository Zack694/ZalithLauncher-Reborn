package com.movtery.zalithlauncher.ui.subassembly.filelist

import android.graphics.drawable.Drawable
import com.movtery.zalithlauncher.utils.stringutils.SortStrings.Companion.compareChar
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.Date

class FileItemBean(
    @JvmField val name: String,
    @JvmField val date: Date?,
    @JvmField val size: Long?
) : Comparable<FileItemBean?> {
    @JvmField var image: Drawable? = null
    @JvmField var remoteIconUrl: String? = null
    @JvmField var file: File? = null
    @JvmField var isHighlighted: Boolean = false
    @JvmField var isCanCheck: Boolean = true
    @JvmField var displayName: String? = null
    @JvmField var subtitle: String? = null
    @JvmField var modVersion: String? = null
    @JvmField var isDisabled: Boolean = false
    @JvmField var updateStatus: UpdateUiStatus = UpdateUiStatus.NONE
    @JvmField var updateText: String? = null


    constructor(file: File) : this(
        file.name,
        Date(file.lastModified()),
        if (file.isFile) FileUtils.sizeOf(file) else null
    ) {
        this.file = file
    }

    constructor(name: String, image: Drawable?) : this(name, null as Date?, null as Long?) {
        this.image = image
    }

    constructor(name: String, date: Date, image: Drawable?) : this(name, date, null as Long?) {
        this.image = image
    }

    override fun compareTo(other: FileItemBean?): Int {
        other ?: throw NullPointerException("Cannot compare to null.")

        val thisName = file?.name ?: name
        val otherName = other.file?.name ?: other.name

        if (this.file != null && file!!.isDirectory) {
            if (other.file != null && !other.file!!.isDirectory) {
                return -1
            }
        } else if (other.file != null && other.file!!.isDirectory) {
            return 1
        }

        return compareChar(thisName, otherName)
    }

    override fun toString(): String {
        return "FileItemBean{" +
                "file=" + file +
                ", name='" + name + '\'' +
                ", displayName='" + displayName + '\'' +
                ", subtitle='" + subtitle + '\'' +
                ", remoteIconUrl='" + remoteIconUrl + '\'' +
                ", isDisabled=" + isDisabled +
                ", updateStatus=" + updateStatus +
                ", updateText='" + updateText + '\'' +
                '}'
    }

    enum class UpdateUiStatus {
        NONE,
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        UNKNOWN
    }
}