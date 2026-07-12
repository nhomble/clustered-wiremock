package io.nhomble.clusteredwiremock.stub;

import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

// StubMapping <-> JSON via WireMock's Json mapper (the form it persists to mappings files), preserving
// every stub field. WireMock's own insertionIndex is excluded from this JSON; we order via StubRecord's
// ULID instead.
public final class StubMappingCodec {

  private StubMappingCodec() {}

  public static String encode(StubMapping stub) {
    return Json.write(stub);
  }

  public static StubMapping decode(String json) {
    return Json.read(json, StubMapping.class);
  }
}
