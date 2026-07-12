# Reference: architecture

Information-oriented. Dry facts about the pieces. For *why*, see the [Explanation](explanation.md).

## Component map

```
        ClusteredExtensionFactory   (ServiceLoader; joins the cluster; returns the extensions below)
                    │
   ┌────────────────┼─────────────────────────────┐
StubReplicator            JournalExporter     ClusterJournalAdminApi
(StubLifecycleListener    (ServeEventListener) (AdminApiExtension)
 + entry listener)             │                    │
   │                           └──── SharedJournal ──┘
   │                                     │
IMap<UUID,String>              IMap<UUID,JournalEntry>
 "<stubMap>"                    "<journalMap>"
   └───────────────  HazelcastInstance  ─────────────┘
                (one embedded member per WireMock node)
```

## How it hooks into WireMock

The extension is discovered via ServiceLoader and returns three WireMock extensions. It never replaces
WireMock's stores; it reacts to WireMock's own events and replays into the native stores / a new
endpoint.

| WireMock surface | Extension point | Effect |
|------------------|-----------------|--------|
| stub created (`stubFor`, `POST /__admin/mappings`, `/import`) | `StubLifecycleListener.afterStubCreated` | write to stub map → peers replay into their native store |
| stub edited (`editStub`, `PUT /__admin/mappings/{id}`) | `afterStubEdited` | write to stub map → peers `editStubMapping` |
| stub removed (`removeStub`, `DELETE`) | `afterStubRemoved` | remove from stub map → peers `removeStubMapping` |
| stubs reset (`resetMappings`) | `afterStubsReset` | clear stub map → peers `resetMappings` |
| request served | `ServeEventListener.afterComplete` | append the event to the shared journal map |
| — (new route) | `AdminApiExtension` | `GET`/`DELETE /__admin/cluster/requests` |

Native per-node `GET /__admin/requests` / `verify()` stay local (WireMock has no API to insert an event
into a node's journal); the cluster-wide view is `GET /__admin/cluster/requests`.

## Classes

| Class | Package | Role |
|-------|---------|------|
| `ClusteredExtensionFactory` | `…clusteredwiremock.extension` | ServiceLoader entry point; joins the cluster, returns the three extensions. `ClusteredExtensionFactory(HazelcastInstance)` for embedding/tests. |
| `StubReplicator` | `…clusteredwiremock.extension` | `StubLifecycleListener` + Hazelcast entry listener; replicates stubs into native stores (loop-guarded). |
| `JournalExporter` | `…clusteredwiremock.extension` | `ServeEventListener` (AFTER_COMPLETE) → shared journal. |
| `ClusterJournalAdminApi` | `…clusteredwiremock.extension` | `AdminApiExtension`; serves `GET`/`DELETE /__admin/cluster/requests`. |
| `SharedJournal` | `…clusteredwiremock.journal` | The shared journal over `IMap<UUID, JournalEntry>`: `add` / `getAll` / `clear`. |
| `JournalEntry` | `…clusteredwiremock.journal` | `DataSerializable` value: `String ulid`, `String eventJson`. |
| `ServeEventCodec` | `…clusteredwiremock.journal` | `ServeEvent` ⇄ JSON via WireMock's `Json`. |
| `StubMappingCodec` | `…clusteredwiremock.stub` | `StubMapping` ⇄ JSON via WireMock's `Json`. |
| `HazelcastInstances` | `io.nhomble.clusteredwiremock` | Factory: `newMember(ClusterConfig)` → configured `HazelcastInstance`. |
| `ClusterConfig` | `io.nhomble.clusteredwiremock` | Immutable record: cluster name, map names, members, k8s service, port. |

## Endpoints

| Route | Effect |
|-------|--------|
| `GET /__admin/cluster/requests` | Cluster-wide journal, newest first: `{ "total": N, "requests": [...] }`. |
| `DELETE /__admin/cluster/requests` | Clears the shared journal for the whole cluster. |

## Distributed data structures

| Name | Type | Contents | Key |
|------|------|----------|-----|
| `<journalMap>` (default `wiremock-request-journal`) | `IMap` | `JournalEntry` (`ulid` + event JSON) | serve-event `UUID` |
| `<stubMap>` (default `wiremock-stubs`) | `IMap` | stub JSON | stub `UUID` |

Ordering ULIDs are minted node-locally via `com.github.f4b6a3:ulid-creator` — no distributed Hazelcast
structure is involved in id assignment.

## Configuration (environment)

Read by `ClusterConfig.fromEnv()` in the extension factory. All optional.

| Variable | Default | Meaning |
|----------|---------|---------|
| `WIREMOCK_CLUSTER_NAME` | `clustered-wiremock` | Hazelcast cluster name (isolation) |
| `WIREMOCK_CLUSTER_PORT` | `5701` | Hazelcast member port |
| `WIREMOCK_CLUSTER_MEMBERS` | – | comma-separated `host[:port]` peers → TCP/IP discovery |
| `WIREMOCK_CLUSTER_K8S_SERVICE_NAME` | – | headless Service → Kubernetes API discovery (highest precedence) |
| `WIREMOCK_JOURNAL_MAP` | `wiremock-request-journal` | shared journal map name |
| `WIREMOCK_STUB_MAP` | `wiremock-stubs` | shared stub map name |

Discovery precedence: k8s service → members (TCP/IP) → multicast.

WireMock server options (port, root dir, etc.) come from the **stock image / standard WireMock args** —
this library only adds clustering.

## Hazelcast member configuration

`HazelcastInstances.newMember` sets, on a fresh `Config`:

| Setting | Value | Reason |
|---------|-------|--------|
| cluster name | `ClusterConfig.clusterName` | cluster isolation |
| `hazelcast.logging.type` | `jdk` | SLF4J is shaded in the WireMock image → `NoClassDefFoundError` if forced |
| `hazelcast.phone.home.enabled` | `false` | no usage telemetry |
| network port | `ClusterConfig.port`, auto-increment on | multiple members per host (tests) |
| join auto-detection | off | be explicit about discovery |
| join k8s / TCP-IP / multicast | one enabled per precedence above | k8s → members → multicast |

## Version matrix

| Dependency | Version |
|------------|---------|
| WireMock | 3.13.2 (provided by the image/host) |
| Hazelcast | 5.5.0 |
| ulid-creator | 5.2.3 |
| Java | 17 (extension jar targets 17 to load in the stock image) |

## Scope

Shared: **stub mappings**, **request journal** (via `/__admin/cluster/requests`). Node-local: native
`/__admin/requests`, scenario state, settings, `__files`.
