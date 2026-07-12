package io.nhomble.clusteredwiremock.journal;

import com.github.f4b6a3.ulid.UlidCreator;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

// The cluster-wide request journal, backed by a Hazelcast map. Each node appends its completed serve
// events here; any node reads the union. A distributed map is unordered, so each entry carries a
// time-ordered ULID and getAll() sorts by it descending (newest first).
public final class SharedJournal {

  private final IMap<UUID, JournalEntry> events;

  public SharedJournal(HazelcastInstance hazelcast, String mapName) {
    this.events = hazelcast.getMap(mapName);
  }

  public void add(ServeEvent event) {
    String ulid = UlidCreator.getMonotonicUlid().toString();
    events.put(event.getId(), new JournalEntry(ulid, ServeEventCodec.encode(event)));
  }

  public List<ServeEvent> getAll() {
    return events.values().stream()
        .sorted(Comparator.comparing(JournalEntry::ulid).reversed())
        .map(e -> ServeEventCodec.decode(e.eventJson()))
        .toList();
  }

  public void clear() {
    events.clear();
  }
}
