package io.nhomble.clusteredwiremock;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

// Minimal JDK HTTP helper for exercising WireMock nodes in tests.
final class TestHttp {

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private TestHttp() {}

  static int get(String url) {
    return send("GET", url, null).statusCode();
  }

  static String getBody(String url) {
    return body(send("GET", url, null));
  }

  static int postJson(String url, String jsonBody) {
    return send("POST", url, jsonBody).statusCode();
  }

  static int putJson(String url, String jsonBody) {
    return send("PUT", url, jsonBody).statusCode();
  }

  static int delete(String url) {
    return send("DELETE", url, null).statusCode();
  }

  private static HttpResponse<String> send(String method, String url, String jsonBody) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
    if (jsonBody == null) {
      builder.method(method, BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(jsonBody));
    }
    try {
      return CLIENT.send(builder.build(), BodyHandlers.ofString());
    } catch (IOException e) {
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private static String body(HttpResponse<String> response) {
    return response.body();
  }
}
