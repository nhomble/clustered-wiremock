# Tutorial: your first WireMock cluster

By the end you'll have **two stock WireMock containers** that share state through the extension: a stub
registered on one serves on the other, and each sees the whole cluster's request journal.

## Prerequisites

- Docker + Docker Compose
- JDK 17+ and Maven (to build the extension jar)

## Step 1 — build the extension jar

```bash
mvn package
ls target/clustered-wiremock-*-extension.jar
```

That jar is our code + Hazelcast + ulid-creator, with WireMock excluded (the image provides it).

## Step 2 — two nodes with docker-compose

The repo's [`docker-compose.yml`](../docker-compose.yml) defines two services on the **stock**
`wiremock/wiremock` image, each mounting the extension jar onto the classpath:

```yaml
x-wiremock: &wiremock
  image: wiremock/wiremock:3.13.2
  volumes:
    - ./target/clustered-wiremock-0.1.0-SNAPSHOT-extension.jar:/var/wiremock/extensions/clustered-wiremock.jar:ro
  environment:
    WIREMOCK_CLUSTER_MEMBERS: "node-a,node-b"

services:
  node-a: { <<: *wiremock, ports: ["8081:8080"] }
  node-b: { <<: *wiremock, ports: ["8082:8080"] }
```

```bash
docker compose up
```

WireMock's ServiceLoader auto-loads the extension (no flags). Watch the logs for `Members {size:2 ...}`
— the two nodes found each other and formed a Hazelcast cluster.

## Step 3 — register a stub on node A

```bash
curl -X POST localhost:8081/__admin/mappings \
  -d '{"request":{"method":"GET","url":"/ping"},"response":{"status":200,"body":"pong"}}'
```

## Step 4 — serve it from node B

Node B never received that stub over HTTP. Yet:

```bash
curl localhost:8082/ping        # -> pong
```

The extension replicated the stub into node B's **native** store, so matching is fully native there.

## Step 5 — the shared journal

Hit node A, then read the cluster journal from node B:

```bash
curl localhost:8081/hello-from-a
curl localhost:8082/__admin/cluster/requests   # includes /hello-from-a
```

Every node exports its served requests to the shared journal; `GET /__admin/cluster/requests` on any
node returns the union. (The stock per-node `GET /__admin/requests` stays local — see the
[Explanation](explanation.md#6-journal--a-read-time-union-because-you-cant-write-the-native-one).)

## What you learned

- The whole thing is a jar you drop onto a stock WireMock's classpath.
- Nodes self-cluster via Hazelcast (`WIREMOCK_CLUSTER_MEMBERS` here; Kubernetes and multicast are
  [other options](how-to.md)).
- Stubs replicate into native stores; the journal is shared at `/__admin/cluster/requests`.

Next: the [How-to](how-to.md) for Kubernetes and other setups, or the [Explanation](explanation.md) for
how it works under the hood.
