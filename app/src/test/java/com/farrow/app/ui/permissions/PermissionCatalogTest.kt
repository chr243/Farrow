package com.farrow.app.ui.permissions

import com.farrow.app.shizuku.ShizukuState
import org.junit.Assert.*
import org.junit.Test

class PermissionCatalogTest {
    private val none = PermissionCatalog.Status(false, false, false, false, ShizukuState.NOT_INSTALLED, false, false, false, false)

    @Test fun listsEveryPermissionWithTheRightAction() {
        val rows = PermissionCatalog.rows(none).associateBy { it.id }
        assertEquals(setOf("all_files", "notifications", "termux", "shizuku", "accessibility", "overlay", "battery", "install"), rows.keys)
        assertTrue(rows.values.none { it.granted })
        assertEquals("Get Termux", rows["termux"]!!.action)
        assertEquals("Get Shizuku", rows["shizuku"]!!.action)
        val r2 = PermissionCatalog.rows(none.copy(termuxInstalled = true, shizuku = ShizukuState.NO_PERMISSION)).associateBy { it.id }
        assertEquals("Grant", r2["termux"]!!.action); assertEquals("Grant", r2["shizuku"]!!.action)
        assertEquals("Open Shizuku", PermissionCatalog.rows(none.copy(shizuku = ShizukuState.NOT_RUNNING)).first { it.id == "shizuku" }.action)
    }

    @Test fun grantedRowsAreMarked() {
        val all = PermissionCatalog.Status(true, true, true, true, ShizukuState.READY, true, true, true, true)
        assertTrue(PermissionCatalog.rows(all).all { it.granted })
        assertNull(PermissionCatalog.rows(all).first { it.id == "shizuku" }.action)
    }
}
