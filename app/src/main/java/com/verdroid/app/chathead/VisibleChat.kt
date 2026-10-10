package com.verdroid.app.chathead

/** The chat detail screen currently shown in MainActivity (null when another route is visible). */
object VisibleChat {
    @Volatile var taskId: Long? = null
}
