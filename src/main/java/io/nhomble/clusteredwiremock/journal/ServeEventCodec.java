package io.nhomble.clusteredwiremock.journal;

import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

// ServeEvent <-> JSON via WireMock's own Json mapper (the one that renders /__admin/requests), so a
// round-trip preserves every field WireMock exposes.
final class ServeEventCodec {

  private ServeEventCodec() {}

  static String encode(ServeEvent event) {
    return Json.write(event);
  }

  static ServeEvent decode(String json) {
    return Json.read(json, ServeEvent.class);
  }
}
