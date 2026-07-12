package io.nhomble.clusteredwiremock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

// Exhaustive end-to-end coverage of the extension on the stock wiremock/wiremock image: our jar in
// /var/wiremock/extensions (ServiceLoader auto-load), nodes clustered over a shared Docker network.
// node-a and node-b run for the whole class; node-c is started mid-test to prove late-joiner sync.
// Every test uses unique URLs/ids so they are order-independent and need no per-test reset.
@Testcontainers(disabledWithoutDocker = true)
class ClusteredWireMockContainerIT {

  private static final DockerImageName WIREMOCK = DockerImageName.parse("wiremock/wiremock:3.13.2");
  private static final int PORT = 8080;
  private static final String MEMBERS = "node-a,node-b,node-c";
  private static final String EXT_PATH = "/var/wiremock/extensions/clustered-wiremock.jar";
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private static Network network;
  private static GenericContainer<?> nodeA;
  private static GenericContainer<?> nodeB;

  @BeforeAll
  static void startCluster() {
    network = Network.newNetwork();
    nodeA = node("node-a").withNetwork(network);
    nodeB = node("node-b").withNetwork(network);
    nodeA.start();
    nodeB.start();
  }

  @AfterAll
  static void stopCluster() {
    if (nodeA != null) nodeA.stop();
    if (nodeB != null) nodeB.stop();
    if (network != null) network.close();
  }

  private static GenericContainer<?> node(String alias) {
    return new GenericContainer<>(WIREMOCK)
        .withNetworkAliases(alias)
        .withCopyFileToContainer(MountableFile.forHostPath(extensionJar()), EXT_PATH)
        .withEnv("WIREMOCK_CLUSTER_MEMBERS", MEMBERS)
        .withExposedPorts(PORT)
        .waitingFor(Wait.forHttp("/__admin/mappings").forPort(PORT).forStatusCode(200));
  }

  private static String url(GenericContainer<?> node) {
    return "http://" + node.getHost() + ":" + node.getMappedPort(PORT);
  }

  private static String stub(String id, String path, String body) {
    return "{\"id\":\""
        + id
        + "\",\"request\":{\"method\":\"GET\",\"url\":\""
        + path
        + "\"},\"response\":{\"status\":200,\"body\":\""
        + body
        + "\"}}";
  }

  private static void awaitBody(String url, String contains) {
    await().atMost(TIMEOUT).untilAsserted(() -> assertThat(TestHttp.getBody(url)).contains(contains));
  }

  private static void awaitStatus(String url, int status) {
    await().atMost(TIMEOUT).untilAsserted(() -> assertThat(TestHttp.get(url)).isEqualTo(status));
  }

  // ---- stub replication ----

  @Test
  void createOnAServesOnB() {
    assertThat(TestHttp.postJson(url(nodeA) + "/__admin/mappings", stub(id(1), "/a2b", "hi-a")))
        .isEqualTo(201);
    awaitStatus(url(nodeB) + "/a2b", 200);
    assertThat(TestHttp.getBody(url(nodeB) + "/a2b")).isEqualTo("hi-a");
  }

  @Test
  void createOnBServesOnA() {
    assertThat(TestHttp.postJson(url(nodeB) + "/__admin/mappings", stub(id(2), "/b2a", "hi-b")))
        .isEqualTo(201);
    awaitStatus(url(nodeA) + "/b2a", 200);
    assertThat(TestHttp.getBody(url(nodeA) + "/b2a")).isEqualTo("hi-b");
  }

  @Test
  void editOnAReplicatesToB() {
    String id = id(3);
    TestHttp.postJson(url(nodeA) + "/__admin/mappings", stub(id, "/edit", "v1"));
    awaitBody(url(nodeB) + "/edit", "v1");

    assertThat(TestHttp.putJson(url(nodeA) + "/__admin/mappings/" + id, stub(id, "/edit", "v2")))
        .isEqualTo(200);
    awaitBody(url(nodeB) + "/edit", "v2");
  }

  @Test
  void removeOnAReplicatesToB() {
    String id = id(4);
    TestHttp.postJson(url(nodeA) + "/__admin/mappings", stub(id, "/rm", "here"));
    awaitStatus(url(nodeB) + "/rm", 200);

    assertThat(TestHttp.delete(url(nodeA) + "/__admin/mappings/" + id)).isEqualTo(200);
    awaitStatus(url(nodeB) + "/rm", 404);
  }

  @Test
  void resetOnANukesStubsClusterWide() {
    TestHttp.postJson(url(nodeA) + "/__admin/mappings", stub(id(5), "/reset-a", "x"));
    TestHttp.postJson(url(nodeB) + "/__admin/mappings", stub(id(6), "/reset-b", "y"));
    awaitStatus(url(nodeB) + "/reset-a", 200);
    awaitStatus(url(nodeA) + "/reset-b", 200);

    assertThat(TestHttp.postJson(url(nodeA) + "/__admin/mappings/reset", null)).isEqualTo(200);

    awaitStatus(url(nodeB) + "/reset-a", 404);
    awaitStatus(url(nodeB) + "/reset-b", 404);
    awaitStatus(url(nodeA) + "/reset-a", 404);
  }

  // ---- shared journal ----

  @Test
  void journalIsUnionOfAllNodes() {
    TestHttp.get(url(nodeA) + "/ju-a");
    TestHttp.get(url(nodeB) + "/ju-b");

    // Each node's cluster journal shows traffic served by the other node.
    awaitBody(url(nodeA) + "/__admin/cluster/requests", "/ju-b");
    awaitBody(url(nodeB) + "/__admin/cluster/requests", "/ju-a");
  }

  @Test
  void deleteClusterJournalClearsAllNodes() {
    TestHttp.get(url(nodeA) + "/jd-marker");
    awaitBody(url(nodeB) + "/__admin/cluster/requests", "/jd-marker");

    assertThat(TestHttp.delete(url(nodeB) + "/__admin/cluster/requests")).isEqualTo(200);

    await()
        .atMost(TIMEOUT)
        .untilAsserted(
            () ->
                assertThat(TestHttp.getBody(url(nodeA) + "/__admin/cluster/requests"))
                    .doesNotContain("/jd-marker"));
  }

  // ---- late joiner ----

  @Test
  void lateJoinerSyncsExistingStubs() {
    // A stub already exists in the cluster before node-c joins.
    TestHttp.postJson(url(nodeA) + "/__admin/mappings", stub(id(7), "/late", "synced"));
    awaitStatus(url(nodeB) + "/late", 200);

    try (GenericContainer<?> nodeC = node("node-c").withNetwork(network)) {
      nodeC.start();
      // node-c's Extension.start() pulls the cluster's existing stubs into its native store.
      awaitStatus(url(nodeC) + "/late", 200);
      assertThat(TestHttp.getBody(url(nodeC) + "/late")).isEqualTo("synced");
    }
  }

  private static String id(int n) {
    return "00000000-0000-0000-0000-0000000000" + String.format("%02d", n);
  }

  private static Path extensionJar() {
    try (DirectoryStream<Path> jars =
        Files.newDirectoryStream(Path.of("target"), "*-extension.jar")) {
      for (Path jar : jars) {
        return jar.toAbsolutePath();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    throw new IllegalStateException(
        "No *-extension.jar in target/. Run `mvn package` before the integration tests.");
  }
}
