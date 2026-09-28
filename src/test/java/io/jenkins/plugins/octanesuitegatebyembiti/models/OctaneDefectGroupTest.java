package io.jenkins.plugins.octanesuitegatebyembiti.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class OctaneDefectGroupTest {
  @Test
  public void normalizesTypesCaseInsensitivelyAndRemovesDuplicates() {
    OctaneDefectGroup group = group("Major", "Critical, VERY HIGH, very_high, unspecified");

    assertEquals(List.of("critical", "veryhigh", "unspecified"), group.getNormalizedTypes());
    assertTrue(group.getValidationError().isEmpty());
  }

  @Test
  public void rejectsUnknownTypesAndReservedNames() {
    assertFalse(group("major", "Severe").getValidationError().isEmpty());
    assertFalse(group("Critical", "Critical").getValidationError().isEmpty());
    assertFalse(group("very_high", "Critical").getValidationError().isEmpty());
    assertFalse(group("majorCount", "Critical").getValidationError().isEmpty());
  }

  private OctaneDefectGroup group(String name, String types) {
    OctaneDefectGroup group = new OctaneDefectGroup(name);
    group.setTypes(types);
    return group;
  }
}
