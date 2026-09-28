package io.jenkins.plugins.octanesuitegatebyembiti.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class OctaneReportJsonTest {
  @Test
  public void escapesStoredTextAndKeysWithoutChangingJsonValuesOrRenderedHtmlFragments()
      throws Exception {
    String hostile = "</script><svg onload=alert('x')>&\"";
    Map<String, Object> value =
        Map.of(hostile, hostile, "html", "<span>&lt;safe&gt;</span>", "count", 4);
    String json = OctaneReportJson.writeString(value);
    assertFalse(json.contains("<"));
    assertFalse(json.contains(">"));
    assertFalse(json.contains("&"));
    assertFalse(json.contains("'"));
    var decoded = OctaneReportJson.readObject(OctaneReportJson.writeBytes(value));
    assertEquals(hostile, decoded.path(hostile).asString());
    assertEquals("<span>&lt;safe&gt;</span>", decoded.path("html").asString());
    assertEquals(4, decoded.path("count").asInt());
  }

  @Test
  public void rejectsNonObjectsAndTrailingContent() {
    for (String invalid : List.of("[]", "null", "{} {}", "<script>bad</script>")) {
      assertThrows(
          IOException.class,
          () -> OctaneReportJson.readObject(invalid.getBytes(StandardCharsets.UTF_8)));
    }
  }

  @Test
  public void serializesNestedDataAtTheResponseBoundaryWithoutClosingTheWriter() throws Exception {
    String hostile = "</script><svg onload=alert('x')>&\"\r\n";
    StringWriter writer =
        new StringWriter() {
          @Override
          public void close() {
            throw new AssertionError("The servlet owns its response writer");
          }
        };
    var value = Map.of("bars", List.of(Map.of(hostile, hostile)), "count", 1);

    OctaneReportJson.writeTo(writer, value);

    assertEquals(OctaneReportJson.writeString(value), writer.toString());
    assertFalse(writer.toString().contains("<"));
    assertFalse(writer.toString().contains("&"));
    var decoded = OctaneReportJson.readObject(writer.toString().getBytes(StandardCharsets.UTF_8));
    assertEquals(hostile, decoded.path("bars").path(0).path(hostile).asString());
    assertEquals(1, decoded.path("count").asInt());
  }
}
