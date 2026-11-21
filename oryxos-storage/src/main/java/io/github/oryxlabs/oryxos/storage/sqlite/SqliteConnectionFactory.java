package io.github.oryxlabs.oryxos.storage.sqlite;

import com.zaxxer.hikari.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import org.sqlite.*;

public final class SqliteConnectionFactory {
  public HikariDataSource create(WorkspaceHandle workspace) {
    SQLiteConfig sqlite = new SQLiteConfig();
    sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
    sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
    sqlite.enforceForeignKeys(true);
    sqlite.setBusyTimeout(5000);
    sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
    var source = new SQLiteDataSource(sqlite);
    source.setUrl("jdbc:sqlite:" + workspace.database().toUri());
    HikariConfig pool = new HikariConfig();
    pool.setDataSource(source);
    pool.setMaximumPoolSize(4);
    pool.setMinimumIdle(4);
    pool.setConnectionTimeout(5000);
    pool.setConnectionInitSql(
        "PRAGMA wal_autocheckpoint=0; PRAGMA cache_size=-16000; PRAGMA temp_store=MEMORY");
    pool.setInitializationFailTimeout(-1);
    pool.setPoolName("oryxos-" + java.util.UUID.randomUUID());
    return new HikariDataSource(pool);
  }
}
