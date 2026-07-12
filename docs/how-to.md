# How-to guides

Task-focused recipes. Each is independent — jump to the one you need.

- [Add to the stock WireMock Docker image](#add-to-the-stock-wiremock-docker-image)
- [Run on Kubernetes (Deployment / ReplicaSet)](#run-on-kubernetes-deployment--replicaset)
- [Cluster across containers with TCP/IP discovery](#cluster-across-containers-with-tcpip-discovery)
- [Change the map or cluster names](#change-the-map-or-cluster-names)
- [Upload stubs over the admin API](#upload-stubs-over-the-admin-api)
- [Embed the extension in a Java app (advanced)](#embed-the-extension-in-a-java-app-advanced)
- [Verify it is actually clustered](#verify-it-is-actually-clustered)

---

## Add to the stock WireMock Docker image

Put the **extension jar** (`mvn package` → `target/clustered-wiremock-*-extension.jar`) on a stock
WireMock's classpath. WireMock's ServiceLoader auto-scan (on by default) loads it with no
`--extensions` flag. Two ways:

Bake it into an image — the example [`Dockerfile`](../Dockerfile):

```dockerfile
FROM wiremock/wiremock:3.13.2
COPY target/clustered-wiremock-*-extension.jar /var/wiremock/extensions/clustered-wiremock.jar
```

```bash
docker build -t clustered-wiremock .
```

…or mount it onto the stock image at run time (no build):

```yaml
services:
  wiremock:
    image: wiremock/wiremock:3.13.2
    ports: ["8080-8090:8080"]
    volumes:
      - ./target/clustered-wiremock-0.1.0-SNAPSHOT-extension.jar:/var/wiremock/extensions/clustered-wiremock.jar:ro
    environment:
      WIREMOCK_CLUSTER_MEMBERS: "wiremock"   # peers for Hazelcast TCP/IP discovery
```

```bash
docker compose up --scale wiremock=3
```

Now a stub added on any replica (`POST /__admin/mappings`) serves on all of them, and each replica's
`GET /__admin/cluster/requests` returns the whole cluster's journal.

> The extension jar bundles Hazelcast + ulid-creator but **excludes** WireMock (the image provides it).
> It targets Java 17 so it loads in the stock image's JRE.

Note: the native `GET /__admin/requests` / `verify()` stay node-local — use `GET /__admin/cluster/requests`
for the cluster view (see [Explanation § 6](explanation.md#6-journal--a-read-time-union-because-you-cant-write-the-native-one)).

## Run on Kubernetes (Deployment / ReplicaSet)

Pod IPs are dynamic and a ReplicaSet scales, so neither multicast nor a static member list works. Use
**Hazelcast's Kubernetes discovery**: set `WIREMOCK_CLUSTER_K8S_SERVICE_NAME` to a **headless Service**
that selects the pods, and Hazelcast queries the k8s API for their endpoints (pod IPs). Dynamic IPs and
`kubectl scale` then just work.

Requirements (all in the example manifest [`k8s/clustered-wiremock.yaml`](../k8s/clustered-wiremock.yaml)):

- A **headless Service** (`clusterIP: None`, `publishNotReadyAddresses: true`) whose name you pass in
  `WIREMOCK_CLUSTER_K8S_SERVICE_NAME`, exposing port 5701.
- **RBAC** — a ServiceAccount + Role granting `get`/`list` on `endpoints`, `pods`, `services` (and
  `endpointslices`), bound to the pods; Hazelcast reads these to find peers.
- An image with the extension baked in (the repo [`Dockerfile`](../Dockerfile)).

```bash
docker build -t <registry>/clustered-wiremock:0.1.0 . && docker push <registry>/clustered-wiremock:0.1.0
kubectl apply -f k8s/clustered-wiremock.yaml
kubectl scale deployment/wiremock --replicas=5     # cluster grows/shrinks automatically
```

Route client traffic through the normal `wiremock` Service (port 80 → 8080); the headless
`wiremock-hazelcast` Service is only for member discovery on 5701.

This path is verified end-to-end by `ClusteredWireMockK8sIT`, which boots an in-container **k3s**
cluster, deploys two pods, and asserts a stub replicates across them via k8s discovery. It's heavy, so
it's excluded from the default build — run it with:

```bash
mvn verify -Pk8s -Dit.test=ClusteredWireMockK8sIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

## Cluster across containers with TCP/IP discovery

Container networks usually block multicast. List the peers explicitly with
`WIREMOCK_CLUSTER_MEMBERS` (comma-separated `host[:port]`, resolved on the shared network):

```yaml
services:
  node-a:
    image: clustered-wiremock
    environment:
      WIREMOCK_CLUSTER_MEMBERS: "node-a,node-b"
    ports: ["8081:8080"]
  node-b:
    image: clustered-wiremock
    environment:
      WIREMOCK_CLUSTER_MEMBERS: "node-a,node-b"
    ports: ["8082:8080"]
```

The Hazelcast member port (5701) only needs to be reachable **between** containers on the shared
network — it does not need publishing to the host.

## Change the map or cluster names

Run several independent clusters on one network by giving each a distinct cluster name (and, optionally,
map names) via the environment. All nodes in one cluster must agree.

```yaml
environment:
  WIREMOCK_CLUSTER_NAME: team-a-cluster
  WIREMOCK_JOURNAL_MAP: team-a-journal
  WIREMOCK_STUB_MAP: team-a-stubs
```

## Upload stubs over the admin API

No special handling needed. The admin API and the Java client both fire the `StubLifecycleListener` the
extension hooks, so a stub uploaded over HTTP on one node serves on all nodes:

```bash
curl -X POST http://node-a:8080/__admin/mappings \
  -d '{"request":{"method":"GET","url":"/hi"},"response":{"status":200,"body":"hi"}}'

curl http://node-b:8080/hi        # -> hi
```

`POST /__admin/mappings/import`, edit, and delete all propagate the same way.

## Embed the extension in a Java app (advanced)

Usually you don't need this — a single embedded WireMock (e.g. the JUnit integration) is one instance,
so there's nothing to cluster. But if you run multiple embedded servers that should share state, wire
the extension in with your own Hazelcast instance:

```java
HazelcastInstance hz = HazelcastInstances.newMember(ClusterConfig.defaults());

WireMockServer server = new WireMockServer(
    WireMockConfiguration.options()
        .port(8080)
        .extensions(new ClusteredExtensionFactory(hz)));  // uses your instance; you own its lifecycle
server.start();
```

## Verify it is actually clustered

Check the Hazelcast member count in the logs (`Members {size:N ...}`), or functionally: register a stub
on one node and request it on another, or hit a node then read `GET /__admin/cluster/requests` on
another. If it appears, the cluster is formed and sharing state.
