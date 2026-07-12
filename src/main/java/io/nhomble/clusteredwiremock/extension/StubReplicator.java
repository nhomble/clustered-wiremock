package io.nhomble.clusteredwiremock.extension;

import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.StubLifecycleListener;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryRemovedListener;
import com.hazelcast.map.listener.EntryUpdatedListener;
import com.hazelcast.map.listener.MapClearedListener;
import io.nhomble.clusteredwiremock.stub.StubMappingCodec;
import java.util.UUID;

// Replicates stubs across the cluster while each node's NATIVE store keeps doing the matching — so it
// works as a plain classpath extension (no store replacement). Local stub changes (client or admin
// API) fire StubLifecycleListener and get written to a shared Hazelcast map; a Hazelcast entry
// listener applies other members' changes to this node's store via Admin.
//
// Loop prevention: applying a remote change via Admin re-fires our own StubLifecycleListener (WireMock
// has no re-entrancy guard). Two guards stop the echo — a ThreadLocal set while applying remote
// changes, and skipping map events that originated on this member.
//
// Caveat vs the withStores path: each node's native store assigns its own insertion order as stubs
// arrive, so equal-priority stubs may tie-break differently across nodes. Distinct matchers (the
// common case) are unaffected.
public final class StubReplicator
    implements StubLifecycleListener,
        EntryAddedListener<UUID, String>,
        EntryUpdatedListener<UUID, String>,
        EntryRemovedListener<UUID, String>,
        MapClearedListener {

  private final Admin admin;
  private final IMap<UUID, String> stubs;
  private final ThreadLocal<Boolean> applyingRemote = ThreadLocal.withInitial(() -> Boolean.FALSE);

  public StubReplicator(HazelcastInstance hazelcast, String mapName, Admin admin) {
    this.admin = admin;
    this.stubs = hazelcast.getMap(mapName);
    this.stubs.addEntryListener(this, true);
  }

  @Override
  public String getName() {
    return "clustered-wiremock-stub-replicator";
  }

  // startAll() runs after the stub store is built, so Admin is live here: a late-joining node pulls
  // the cluster's existing stubs into its own store.
  @Override
  public void start() {
    applyingRemote.set(Boolean.TRUE);
    try {
      stubs.values().forEach(json -> admin.addStubMapping(StubMappingCodec.decode(json)));
    } finally {
      applyingRemote.set(Boolean.FALSE);
    }
  }

  // ---- local → cluster ----

  @Override
  public void afterStubCreated(StubMapping stub) {
    publish(stub, () -> stubs.put(stub.getId(), StubMappingCodec.encode(stub)));
  }

  @Override
  public void afterStubEdited(StubMapping oldStub, StubMapping newStub) {
    publish(newStub, () -> stubs.put(newStub.getId(), StubMappingCodec.encode(newStub)));
  }

  @Override
  public void afterStubRemoved(StubMapping stub) {
    publish(stub, () -> stubs.remove(stub.getId()));
  }

  @Override
  public void afterStubsReset() {
    if (!applyingRemote.get()) {
      stubs.clear();
    }
  }

  private void publish(StubMapping stub, Runnable action) {
    if (applyingRemote.get() || stub.getId() == null) {
      return;
    }
    action.run();
  }

  // ---- cluster → local ----

  @Override
  public void entryAdded(com.hazelcast.core.EntryEvent<UUID, String> event) {
    applyRemote(event.getMember().localMember(), () -> admin.addStubMapping(decode(event.getValue())));
  }

  @Override
  public void entryUpdated(com.hazelcast.core.EntryEvent<UUID, String> event) {
    applyRemote(
        event.getMember().localMember(), () -> admin.editStubMapping(decode(event.getValue())));
  }

  @Override
  public void entryRemoved(com.hazelcast.core.EntryEvent<UUID, String> event) {
    applyRemote(event.getMember().localMember(), () -> admin.removeStubMapping(event.getKey()));
  }

  @Override
  public void mapCleared(com.hazelcast.map.MapEvent event) {
    applyRemote(event.getMember().localMember(), admin::resetMappings);
  }

  private void applyRemote(boolean local, Runnable action) {
    if (local) {
      return; // our own write; already applied locally
    }
    applyingRemote.set(Boolean.TRUE);
    try {
      action.run();
    } finally {
      applyingRemote.set(Boolean.FALSE);
    }
  }

  private static StubMapping decode(String json) {
    return StubMappingCodec.decode(json);
  }
}
