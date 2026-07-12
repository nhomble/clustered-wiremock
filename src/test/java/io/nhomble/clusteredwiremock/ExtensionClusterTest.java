package io.nhomble.clusteredwiremock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.hazelcast.core.HazelcastInstance;
import io.nhomble.clusteredwiremock.extension.ClusteredExtensionFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Fast (no Docker) coverage of the extension: two embedded WireMock servers sharing one Hazelcast
// cluster via ClusteredExtensionFactory(hz). Mirrors the container IT — stub replication into native
// stores and the shared journal — with in-JVM feedback. Replication is async, so cross-node reads await.
class ExtensionClusterTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private HazelcastInstance hz1;
  private HazelcastInstance hz2;
  private WireMockServer nodeA;
  private WireMockServer nodeB;

  @BeforeEach
  void startCluster() {
    List<String> members = List.of("127.0.0.1:5701", "127.0.0.1:5702");
    hz1 = HazelcastInstances.newMember(ClusterConfig.withMembers(members));
    hz2 = HazelcastInstances.newMember(ClusterConfig.withMembers(members));
    nodeA = startNode(hz1);
    nodeB = startNode(hz2);
  }

  private static WireMockServer startNode(HazelcastInstance hz) {
    WireMockServer server =
        new WireMockServer(
            WireMockConfiguration.options()
                .dynamicPort()
                .extensions(new ClusteredExtensionFactory(hz)));
    server.start();
    return server;
  }

  @AfterEach
  void stopCluster() {
    if (nodeA != null) nodeA.stop();
    if (nodeB != null) nodeB.stop();
    if (hz1 != null) hz1.shutdown();
    if (hz2 != null) hz2.shutdown();
  }

  @Test
  void stubCreatedOnAServesNativelyOnB() {
    nodeA.stubFor(get(urlEqualTo("/create")).willReturn(aResponse().withStatus(200).withBody("hi")));

    awaitStatus(nodeB.baseUrl() + "/create", 200);
    assertThat(TestHttp.getBody(nodeB.baseUrl() + "/create")).isEqualTo("hi");
  }

  @Test
  void stubEditOnAReplicatesToB() {
    UUID id = UUID.randomUUID();
    nodeA.stubFor(get(urlEqualTo("/edit")).withId(id).willReturn(aResponse().withBody("v1")));
    awaitBody(nodeB.baseUrl() + "/edit", "v1");

    nodeA.editStub(get(urlEqualTo("/edit")).withId(id).willReturn(aResponse().withBody("v2")));
    awaitBody(nodeB.baseUrl() + "/edit", "v2");
  }

  @Test
  void stubRemoveOnAReplicatesToB() {
    StubMapping stub =
        nodeA.stubFor(get(urlEqualTo("/rm")).willReturn(aResponse().withStatus(200)));
    awaitStatus(nodeB.baseUrl() + "/rm", 200);

    nodeA.removeStub(stub);
    awaitStatus(nodeB.baseUrl() + "/rm", 404);
  }

  @Test
  void journalIsSharedViaClusterEndpoint() {
    TestHttp.get(nodeA.baseUrl() + "/seen-on-a");

    awaitBody(nodeB.baseUrl() + "/__admin/cluster/requests", "/seen-on-a");
  }

  private static void awaitStatus(String url, int status) {
    await().atMost(TIMEOUT).untilAsserted(() -> assertThat(TestHttp.get(url)).isEqualTo(status));
  }

  private static void awaitBody(String url, String contains) {
    await().atMost(TIMEOUT).untilAsserted(() -> assertThat(TestHttp.getBody(url)).contains(contains));
  }
}
