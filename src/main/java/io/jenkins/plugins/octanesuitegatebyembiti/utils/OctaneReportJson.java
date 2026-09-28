package io.jenkins.plugins.octanesuitegatebyembiti.utils;

import java.io.IOException;
import java.io.Writer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.SerializableString;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.io.CharacterEscapes;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** JSON encoding for persisted reports and HTTP responses, without changing decoded values. */
public final class OctaneReportJson {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder(
              JsonFactory.builder()
                  .characterEscapes(new HtmlSafeEscapes())
                  .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private OctaneReportJson() {}

  public static byte[] writeBytes(Object value) {
    return MAPPER.writeValueAsBytes(value);
  }

  public static String writeString(Object value) {
    return MAPPER.writeValueAsString(value);
  }

  /** Serializes structured data at the response boundary without closing the servlet's writer. */
  public static void writeTo(Writer writer, Object value) {
    MAPPER.writeValue(writer, value);
  }

  public static ObjectNode readObject(byte[] content) throws IOException {
    try {
      JsonNode data = MAPPER.readTree(content);
      if (data instanceof ObjectNode object) {
        return object;
      }
    } catch (JacksonException e) {
      throw new IOException("Invalid Octane JSON report artifact.", e);
    }
    throw new IOException("Octane report artifact must contain a JSON object.");
  }

  private static final class HtmlSafeEscapes extends CharacterEscapes {
    private static final long serialVersionUID = 1L;
    private final int[] escapes = CharacterEscapes.standardAsciiEscapesForJSON();

    private HtmlSafeEscapes() {
      for (char character : new char[] {'<', '>', '&', '\''}) {
        escapes[character] = CharacterEscapes.ESCAPE_STANDARD;
      }
    }

    @Override
    public int[] getEscapeCodesForAscii() {
      return escapes.clone();
    }

    @Override
    public SerializableString getEscapeSequence(int character) {
      return null;
    }
  }
}
