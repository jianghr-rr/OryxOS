package io.github.oryxlabs.oryxos.storage.memory;
import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
@Entity(name="RemoteLinkEntity") @Table(name="memory_remote_links") @IdClass(RemoteLinkEntity.Key.class)
public class RemoteLinkEntity {
    @Id @Column(name="source_entry_id") String sourceEntryId;
    @Id @Column(name="remote_id") String remoteId;
    @Id @Column(name="workspace_id") String workspaceId;
    @Column(name="verified_at",nullable=false) String verifiedAt;
    public RemoteLinkEntity() { }
    public static class Key implements Serializable {
        public String sourceEntryId,remoteId,workspaceId;
        public Key() { }
        public Key(String source,String remote,String workspace){sourceEntryId=source;remoteId=remote;workspaceId=workspace;}
        public boolean equals(Object o){return o instanceof Key k&&Objects.equals(sourceEntryId,k.sourceEntryId)&&Objects.equals(remoteId,k.remoteId)&&Objects.equals(workspaceId,k.workspaceId);}
        public int hashCode(){return Objects.hash(sourceEntryId,remoteId,workspaceId);}
    }
}
