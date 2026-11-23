package io.github.oryxlabs.oryxos.storage.lifecycle;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Owns a factory, pool and its open EntityManagers; replacement closes all old resources. */
public final class StorageResources implements AutoCloseable {
  private final HikariDataSource dataSource;
  private final EntityManagerFactory factory;
  private final Set<EntityManager> managers = ConcurrentHashMap.newKeySet();
  private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
  private volatile boolean closed;

  public StorageResources(HikariDataSource dataSource, EntityManagerFactory factory) {
    this.dataSource = dataSource;
    this.factory = factory;
  }

  public EntityManager openEntityManager() {
    var readLock = rwLock.readLock();
    readLock.lock();
    try {
      if (closed) throw new IllegalStateException("Storage resources are closed");
      if (managers.size() >= 32) managers.removeIf(manager -> !manager.isOpen());
      var manager = factory.createEntityManager();
      managers.add(manager);
      return manager;
    } finally {
      readLock.unlock();
    }
  }

  public HikariDataSource dataSource() {
    return dataSource;
  }

  public boolean isOpen() {
    return !closed && factory.isOpen() && !dataSource.isClosed();
  }

  @Override
  public void close() {
    var writeLock = rwLock.writeLock();
    writeLock.lock();
    try {
      if (closed) return;
      closed = true;
      try {
        for (var manager : managers) if (manager.isOpen()) manager.close();
      } finally {
        managers.clear();
        try {
          factory.close();
        } finally {
          dataSource.close();
        }
      }
    } finally {
      writeLock.unlock();
    }
  }
}
