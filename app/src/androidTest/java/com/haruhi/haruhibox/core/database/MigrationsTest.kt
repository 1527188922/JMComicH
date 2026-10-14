package com.haruhi.haruhibox.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移测试（**设备/模拟器**）。
 *
 * 为什么必须有这个测试：改动 `@Database(entities = ...)` 而忘记升 version /
 * 补迁移时，**应用会一启动就崩**，而且全新安装不会复现、只有覆盖安装才暴露。
 * P2 加 `t_jm_recent_album` 时就这样漏过一次（真机上覆盖安装才炸）。
 *
 * 这里从 v1 建库、跑 [Migrations.ALL]、再用 Room 打开校验——
 * 校验失败（identity hash 不一致）就是没升 version 或迁移 SQL 与实体不匹配。
 */
@RunWith(AndroidJUnit4::class)
class MigrationsTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HaruhiBoxDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * v1 → v2：新表建好、Room 校验通过、**旧数据不丢**。
     *
     * 特意在 v1 里先写一条书库记录，迁移后仍然在——这正是"不能用
     * destructive migration"的原因。
     */
    @Test
    fun migrate1To2CreatesRecentAlbumTableAndKeepsData() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO t_jm_album (id, name, album_folder_name, collected, create_time, modify_time)
                VALUES (480854, '迁移前的本子', '迁移前的本子_JM480854', 1, '2026-01-01 00:00:00', '2026-01-01 00:00:00')
                """.trimIndent(),
            )
        }

        // 跑完所有迁移后打开；Room 会在这里做 schema 校验
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, *Migrations.ALL)

        db.query("SELECT name, collected FROM t_jm_album WHERE id = 480854").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("迁移前的本子")
            assertThat(cursor.getInt(1)).isEqualTo(1)
        }

        // 新表可用，且 viewed_time 是 NOT NULL
        db.execSQL(
            """
            INSERT INTO t_jm_recent_album (id, name, viewed_time) VALUES (1, '看过的', 1000)
            """.trimIndent(),
        )
        db.query("SELECT COUNT(*) FROM t_jm_recent_album").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }

        db.close()
    }

    /**
     * 当前版本的库**全新创建**后必须能被 Room 打开。
     *
     * 这条覆盖的是"生成 schema 与实体不一致"这类问题（不涉及历史数据），
     * 与上一条互补。
     */
    @Test
    fun currentVersionSchemaIsConsistent() {
        helper.createDatabase(TEST_DB, 2).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, *Migrations.ALL)
        db.close()
    }
}
