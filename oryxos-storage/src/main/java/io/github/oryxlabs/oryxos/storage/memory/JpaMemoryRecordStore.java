package io.github.oryxlabs.oryxos.storage.memory;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.storage.lifecycle.StorageResources;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import java.util.function.*;

/** Every method opens a fresh, short transaction; a remote request never holds an EntityManager. */
public final class JpaMemoryRecordStore implements MemoryRecordStore {
  private final Supplier<StorageResources> resources;
  private final io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator writes;

  public JpaMemoryRecordStore(Supplier<StorageResources> resources) {
    this(resources, null);
  }

  public JpaMemoryRecordStore(
      Supplier<StorageResources> resources,
      io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator writes) {
    this.resources = resources;
    this.writes = writes;
  }

  public void insert(MemoryEntry entry) {
    transaction(
        true,
        em -> {
          if (!entry.remoteIds().isEmpty()
              || entry.backend().equals("mem0")
                  && entry.scope() == MemoryEntry.Scope.ARCHIVAL
                  && entry.writeStatus() == MemoryEntry.WriteStatus.CONFIRMED)
            throw new IllegalArgumentException("Projection requires verified links");
          var e = new MemoryEntryEntity();
          e.id = entry.id().toString();
          e.workspaceId = entry.workspaceId().toString();
          e.scope = entry.scope().name();
          e.rawContent = entry.rawContent();
          e.createdAt = entry.createdAt().toString();
          e.sourceExecutionId = entry.sourceExecutionId().toString();
          e.sourceAgentName = entry.sourceAgentName();
          e.backend = entry.backend();
          e.writeStatus = entry.writeStatus().name();
          em.persist(e);
          return null;
        });
  }

  public void finish(
      UUID workspace, UUID id, MemoryEntry.WriteStatus state, List<String> ids, Instant at) {
    transaction(
        true,
        em -> {
          var e = em.find(MemoryEntryEntity.class, id.toString());
          if (e == null
              || !e.workspaceId.equals(workspace.toString())
              || !e.writeStatus.equals("PREPARED")
              || state == MemoryEntry.WriteStatus.PREPARED)
            throw new IllegalArgumentException("Invalid source transition");
          if (state == MemoryEntry.WriteStatus.CONFIRMED
              && e.backend.equals("mem0")
              && e.scope.equals("ARCHIVAL")
              && ids.isEmpty()) throw new IllegalArgumentException("Missing verified projections");
          if (!ids.isEmpty()
              && (state != MemoryEntry.WriteStatus.CONFIRMED
                  || !e.backend.equals("mem0")
                  || !e.scope.equals("ARCHIVAL")
                  || new HashSet<>(ids).size() != ids.size()))
            throw new IllegalArgumentException("Invalid source links");
          for (String remote : ids) {
            if (remote == null || remote.isBlank())
              throw new IllegalArgumentException("Empty remote id");
            var link = new RemoteLinkEntity();
            link.workspaceId = e.workspaceId;
            link.sourceEntryId = e.id;
            link.remoteId = remote;
            link.verifiedAt = at.toString();
            em.persist(link);
          }
          e.writeStatus = state.name();
          return null;
        });
  }

  public List<MemoryEntry> list(
      UUID workspace, String backend, MemoryEntry.Scope scope, boolean confirmedOnly) {
    return transaction(
        false,
        em -> {
          var query =
              em.createQuery(
                      "select e from MemoryEntryEntity e where e.workspaceId=:workspace and e.backend=:backend and e.scope=:scope"
                          + (confirmedOnly ? " and e.writeStatus='CONFIRMED'" : "")
                          + " order by e.createdAt,e.id",
                      MemoryEntryEntity.class)
                  .setParameter("workspace", workspace.toString())
                  .setParameter("backend", backend)
                  .setParameter("scope", scope.name());
          return query.getResultList().stream().map(e -> entry(em, e)).toList();
        });
  }

  public MemoryEntry find(UUID workspace, UUID id) {
    return transaction(
        false,
        em -> {
          var e = em.find(MemoryEntryEntity.class, id.toString());
          return e != null && e.workspaceId.equals(workspace.toString()) ? entry(em, e) : null;
        });
  }

  public boolean maps(UUID workspace, UUID source, String remote) {
    return transaction(
        false,
        em -> {
          var entry = em.find(MemoryEntryEntity.class, source.toString());
          return entry != null
              && entry.workspaceId.equals(workspace.toString())
              && entry.writeStatus.equals("CONFIRMED")
              && em.find(
                      RemoteLinkEntity.class,
                      new RemoteLinkEntity.Key(source.toString(), remote, workspace.toString()))
                  != null;
        });
  }

  private MemoryEntry entry(EntityManager em, MemoryEntryEntity e) {
    var ids =
        em.createQuery(
                "select r.remoteId from RemoteLinkEntity r where r.workspaceId=:workspace and r.sourceEntryId=:source order by r.remoteId",
                String.class)
            .setParameter("workspace", e.workspaceId)
            .setParameter("source", e.id)
            .getResultList();
    return new MemoryEntry(
        UUID.fromString(e.id),
        UUID.fromString(e.workspaceId),
        MemoryEntry.Scope.valueOf(e.scope),
        e.rawContent,
        Instant.parse(e.createdAt),
        UUID.fromString(e.sourceExecutionId),
        e.sourceAgentName,
        e.backend,
        MemoryEntry.WriteStatus.valueOf(e.writeStatus),
        ids);
  }

  private <T> T transaction(boolean write, Function<EntityManager, T> action) {
    if (writes != null) return write ? writes.write(action) : writes.read(action);
    try (var em = resources.get().openEntityManager()) {
      var tx = em.getTransaction();
      tx.begin();
      try {
        T value = action.apply(em);
        tx.commit();
        return value;
      } catch (RuntimeException e) {
        if (tx.isActive()) tx.rollback();
        throw e;
      }
    } catch (RuntimeException e) {
      throw new OryxError(
          write ? "RECORDING_FAILURE" : "MEMORY_UNAVAILABLE",
          "Local memory ledger operation failed; no empty or success fallback is used");
    }
  }
}
