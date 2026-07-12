# Explanation: how it works, and how Hazelcast is used

Understanding-oriented. The "why" behind the design and every distributed-systems decision.

The whole library is one WireMock **extension**, auto-loaded from the classpath, that makes a set of
stock WireMock instances behave like one: stubs registered on any node serve on all of them, and a
cluster-wide request journal is exposed at a new admin endpoint. State lives in an embedded
[Hazelcast](https://hazelcast.com) grid — the WireMock nodes *are* the cluster, so there's no external
datastore.

---

## 1. Why an extension that *replicates*, not a store that's *shared*

WireMock 3 keeps its state behind a `Stores` interface, and in principle you could hand WireMock a
Hazelcast-backed `Stores` so the journal and stub registry simply *are* distributed maps. But that seam
(`WireMockConfiguration.withStores(...)`) is only reachable when you build the server in Java — and the
whole point here is the **stock WireMock Docker image**, where you can't. Verified against the 3.13.2
source: standalone hard-wires `new DefaultStores(...)`; there is no CLI flag, system property,
ServiceLoader hook, or extension point to replace `Stores`.

So the extension works through WireMock's **behavioural** extension points instead:

- **Stubs** — a `StubLifecycleListener` observes local stub changes and mirrors them to a Hazelcast
  map; a map listener applies peers' changes into *this node's own native store*. Matching stays 100%
  native on every node.
- **Journal** — a `ServeEventListener` exports each completed event to a shared Hazelcast map; an
  `AdminApiExtension` serves the union at `GET /__admin/cluster/requests`.

## 2. `IMap` and partitioning — the core model

State lives in two Hazelcast `IMap`s (distributed maps): the shared journal (`IMap<UUID, JournalEntry>`)
and the stub-replication log (`IMap<UUID, String>`, id → stub JSON).

An `IMap` is **partitioned**: keys are spread across 271 partitions, each owned by one member with a
sync backup on another. Consequences:

- `put`/`remove` by key → one hop to the owner. Cheap.
- `values()` → gathers from every member. Used by the journal endpoint (`getAll`); fine for mock-sized
  data.
- A member can die without data loss (backup); joining a member triggers rebalancing.

## 3. Ordering — `IMap` has none, so we add a ULID

WireMock returns journal events newest-first. An `IMap` is unordered, so each `JournalEntry` carries a
time-ordered [ULID](https://github.com/ulid/spec) (via `com.github.f4b6a3:ulid-creator`,
`getMonotonicUlid()`), and `getAll()` sorts by it descending. A ULID is a 48-bit millisecond timestamp
+ 80 bits of randomness rendered as 26 lexicographically-sortable chars, so a plain `String.compareTo`
reproduces time order across nodes. It's minted **node-locally** — no coordinator, no grid call — which
is exactly why we didn't reach for Hazelcast's CP `IAtomicLong` (needs a Raft group of ≥3) or
`FlakeIdGenerator` (orders timestamp-then-node-id).

**Honest tradeoff:** two events in the same millisecond on different nodes order by their random tails —
stably but arbitrarily within that millisecond, and it assumes reasonably synced clocks. Right precision
for a mock's journal.

## 4. Serialization — two layers

Hazelcast must turn values into bytes. Rather than teach it WireMock's rich types:

1. **Domain → JSON** with WireMock's own `Json` mapper (`ServeEventCodec`, `StubMappingCodec`) — the
   same mapper that renders the admin API, so round-trips preserve every field WireMock exposes.
2. **Wrapper → Hazelcast** via `DataSerializable` (`JournalEntry` = `(ulid, json)`) — explicit
   `writeData`/`readData`, zero registration. The stub map stores the JSON string directly.

So Hazelcast only ever moves small `(String, String)` payloads; the heavy object is already a string.

## 5. Stub replication and the loop guard

`StubReplicator` bridges both directions:

- **local → cluster** — `StubLifecycleListener.afterStub{Created,Edited,Removed,Reset}` fires for stub
  changes made via the Java client *or* the admin API (both funnel through WireMock's
  `AbstractStubMappings`), and writes to the shared map.
- **cluster → local** — a Hazelcast entry listener (`entryAdded/Updated/Removed` + `mapCleared`) applies
  peers' changes to this node's native store via `Admin` (`addStubMapping` / `editStubMapping` /
  `removeStubMapping` / `resetMappings`).

The subtlety is **loop prevention**: applying a remote change through `Admin` re-fires this node's
`StubLifecycleListener`, and WireMock has no re-entrancy guard. Two guards break the echo:

- a `ThreadLocal` flag set while applying a remote change — the `Admin` call fires the lifecycle
  listener synchronously on the same thread, which sees the flag and doesn't re-publish; and
- an origin check — Hazelcast delivers each event to every member including the writer, so a node
  ignores events where `event.getMember().localMember()` is true.

A late-joining node syncs existing stubs in `Extension.start()`, which WireMock calls *after* the stub
store is built (so `Admin` is live).

**Ordering caveat:** each node's native store assigns its own insertion index as replicated stubs
arrive, so two stubs of equal priority could tie-break differently across nodes if they arrive in
different orders. Stubs with distinct matchers (the common case) are unaffected.

## 6. Journal — a read-time union, because you can't write the native one

Here the extension hits a wall the stub side doesn't: WireMock exposes **no API to insert a `ServeEvent`
into a node's native journal** (only the internal serve pipeline writes it). So node B's native
`GET /__admin/requests` physically cannot contain node A's request. Instead every node exports completed
events to the shared journal map, and `ClusterJournalAdminApi` serves the union at
`GET /__admin/cluster/requests` (with `DELETE` to clear it cluster-wide). The stock per-node endpoint
stays local by necessity; the cluster view lives at the new path.

(The admin read endpoints *could* be intercepted with a `RequestFilterV2` to make native `verify()`
cluster-aware, but it means re-implementing WireMock's match/count queries — deliberately out of scope.)

## 7. Discovery — multicast, TCP/IP, or Kubernetes

`HazelcastInstances.newMember` configures join explicitly (`autoDetection` off), by precedence:

- **Kubernetes** when `WIREMOCK_CLUSTER_K8S_SERVICE_NAME` is set — Hazelcast queries the k8s API for a
  headless Service's endpoints (pod IPs). The only mode that fits a Deployment/ReplicaSet, where pod IPs
  are dynamic and the replica count changes.
- **TCP/IP** with an explicit member list (`WIREMOCK_CLUSTER_MEMBERS`) — for containers/CI, where
  multicast is blocked.
- **Multicast** otherwise — LAN auto-discovery, convenient for local dev.

Cluster name isolates clusters (members only join matching names); port auto-increment lets several
members share a host (two in one JVM bind 5701 then 5702 — used by the in-JVM tests).

## 8. Lifecycle

The `ClusteredExtensionFactory` creates the Hazelcast member (from env) and registers a JVM shutdown
hook to stop it. For embedding/tests, `ClusteredExtensionFactory(HazelcastInstance)` takes a
caller-managed instance and leaves its lifecycle to the caller.

## 9. Consistency

Hazelcast `IMap` is **AP** — available and partition-tolerant, not linearizable. Per-key reads see the
owner's latest committed value; there are no cross-key transactions (we never need one). On a
split-brain, both sides keep serving and reconcile on heal via the map's merge policy. This is test
infrastructure, not a system of record, and every "best-effort" choice is that framing made explicit.

## 10. Performance and operational hygiene

- The journal grows unbounded in the shared map — for very long-running clusters you'd want map
  eviction; not configured yet.
- Stub replication events and the journal export are cheap (small payloads, one key each).
- Hazelcast logging routes to **JDK logging** (not SLF4J — the WireMock image shades SLF4J, so forcing
  it throws `NoClassDefFoundError`); **phone-home is disabled**. On Java 17+ Hazelcast asks for
  `--add-opens` for peak performance — optional; it runs correctly without.

---

### Summary

The extension is small because it delegates the hard parts: **WireMock** defines the extension points,
**JSON via WireMock's mapper** handles domain serialization, and **Hazelcast** provides the distributed
maps, cluster discovery, and change notifications. The custom logic is narrow — reproduce WireMock's
ordering on an unordered map with a node-local ULID, replicate stubs into native stores with a two-part
loop guard, and expose the journal union at a new endpoint because the native one can't be written.
