package com.example.passmanager.data.local;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.example.passmanager.data.model.AuditLog;
import com.example.passmanager.data.model.Credential;

// 1. Bump version to 3 for the new Authenticator column
@Database(entities = {Credential.class, AuditLog.class}, version = 3, exportSchema = false)
public abstract class VaultDatabase extends RoomDatabase {

    public abstract CredentialDao credentialDao();
    public abstract AuditLogDao auditLogDao();

    private static volatile VaultDatabase INSTANCE;

    // Original Migration (Audit Log)
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `audit_log_table` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`eventType` TEXT, " +
                    "`isSuccessful` INTEGER NOT NULL)");
        }
    };

    // 2. NEW MIGRATION: Add the totpSecret column to existing credentials
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE credentials_table ADD COLUMN totpSecret TEXT");
        }
    };

    public static VaultDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (VaultDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    VaultDatabase.class, "password_vault_database")
                            // 3. Add BOTH migrations so the app knows how to upgrade from any older version
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                            // No destructive fallback: a missing migration must crash loudly,
                            // never silently wipe the user's vault.
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}