package io.nhomble.clusteredwiremock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

// Real Kubernetes end-to-end: a Deployment (ReplicaSet) of stock-wiremock+extension pods that
// self-cluster via Hazelcast's Kubernetes API discovery (headless Service + RBAC). Runs against an
// in-container k3s cluster — no external kind/minikube. A stub POSTed to one pod serving on another
// proves the pods found each other through the k8s API and formed a cluster.
// Heavy (boots k3s + builds/loads an image): excluded from the default build, run with `mvn verify -Pk8s`.
@Tag("k8s")
@Testcontainers(disabledWithoutDocker = true)
class ClusteredWireMockK8sIT {

  private static final String IMAGE = "clustered-wiremock-k3s:it";
  private static final int PORT = 8080;
  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private static K3sContainer k3s;
  private static KubernetesClient client;

  @BeforeAll
  static void deploy() throws Exception {
    // 1. Build the image (stock WireMock + our extension jar) on the host Docker.
    String builtImage =
        new ImageFromDockerfile(IMAGE, false)
            .withFileFromPath("app.jar", extensionJar())
            .withDockerfileFromBuilder(
                b ->
                    b.from("wiremock/wiremock:3.13.2")
                        .copy("app.jar", "/var/wiremock/extensions/clustered-wiremock.jar")
                        .build())
            .get();

    // 2. Start k3s and load the local image into its containerd (it can't pull an unpushed image).
    k3s = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.31.2-k3s1"));
    k3s.start();
    loadImageIntoK3s(builtImage);

    // 3. fabric8 client from the k3s kubeconfig; apply the manifest with the preloaded image.
    client =
        new KubernetesClientBuilder()
            .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml()))
            .build();
    String manifest = readResource("/k8s-test.yaml").replace("IMAGE_PLACEHOLDER", builtImage);
    client.load(new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8))).create();

    // 4. Wait for both pods to become Ready (readiness probe = WireMock HTTP up = cluster joined).
    client
        .apps()
        .deployments()
        .inNamespace("default")
        .withName("wiremock")
        .waitUntilReady(4, TimeUnit.MINUTES);
  }

  @AfterAll
  static void teardown() {
    if (client != null) client.close();
    if (k3s != null) k3s.stop();
  }

  @Test
  void stubReplicatesAcrossPodsViaK8sDiscovery() throws Exception {
    List<Pod> pods =
        client.pods().inNamespace("default").withLabel("app", "wiremock").list().getItems();
    assertThat(pods).hasSize(2);

    try (LocalPortForward a = forward(pods.get(0));
        LocalPortForward b = forward(pods.get(1))) {
      String podA = "http://localhost:" + a.getLocalPort();
      String podB = "http://localhost:" + b.getLocalPort();

      assertThat(
              TestHttp.postJson(
                  podA + "/__admin/mappings",
                  "{\"request\":{\"method\":\"GET\",\"url\":\"/k8s-shared\"},"
                      + "\"response\":{\"status\":200,\"body\":\"across-pods\"}}"))
          .isEqualTo(201);

      // Replicated to the other pod's native store via the cluster the pods formed over the k8s API.
      await()
          .atMost(TIMEOUT)
          .untilAsserted(() -> assertThat(TestHttp.get(podB + "/k8s-shared")).isEqualTo(200));
      assertThat(TestHttp.getBody(podB + "/k8s-shared")).isEqualTo("across-pods");
    }
  }

  private static LocalPortForward forward(Pod pod) {
    return client
        .pods()
        .inNamespace("default")
        .withName(pod.getMetadata().getName())
        .portForward(PORT);
  }

  private static void loadImageIntoK3s(String image) throws Exception {
    Path tar = Files.createTempFile("cwm-image", ".tar");
    try (InputStream in = DockerClientFactory.lazyClient().saveImageCmd(image).exec()) {
      Files.copy(in, tar, StandardCopyOption.REPLACE_EXISTING);
    }
    k3s.copyFileToContainer(MountableFile.forHostPath(tar), "/tmp/cwm-image.tar");
    Container.ExecResult result =
        k3s.execInContainer("ctr", "-n", "k8s.io", "images", "import", "/tmp/cwm-image.tar");
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
  }

  private static String readResource(String path) {
    try (InputStream in = ClusteredWireMockK8sIT.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
    throw new IllegalStateException("No *-extension.jar in target/. Run `mvn package` first.");
  }
}
