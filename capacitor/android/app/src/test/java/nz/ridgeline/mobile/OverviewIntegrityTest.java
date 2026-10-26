package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import java.io.*;
import java.security.MessageDigest;
import org.junit.Test;

public class OverviewIntegrityTest {
  static String digest(byte[] bytes) throws Exception {
    StringBuilder out = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
      out.append(String.format("%02x", b & 255));
    return out.toString();
  }

  @Test
  public void streamsExactVerifiedBytes() throws Exception {
    byte[] bytes = new byte[100003];
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    OverviewIntegrity.copy(new ByteArrayInputStream(bytes), out, bytes.length, digest(bytes));
    assertArrayEquals(bytes, out.toByteArray());
  }

  @Test
  public void rejectsTampering() throws Exception {
    byte[] bytes = {1, 2, 3};
    String expected = digest(bytes);
    bytes[1] = 4;
    assertThrows(
        IOException.class,
        () ->
            OverviewIntegrity.copy(
                new ByteArrayInputStream(bytes), new ByteArrayOutputStream(), 3, expected));
  }

  @Test
  public void rejectsTruncationAndExcessBytes() throws Exception {
    byte[] bytes = {1, 2, 3};
    String expected = digest(bytes);
    for (int n : new int[] {2, 4})
      assertThrows(
          IOException.class,
          () ->
              OverviewIntegrity.copy(
                  new ByteArrayInputStream(bytes), new ByteArrayOutputStream(), n, expected));
  }

  @Test
  public void enforcesTheResourceLimitBeforeReading() throws Exception {
    assertThrows(
        IOException.class,
        () ->
            OverviewIntegrity.copy(
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                65L * 1024 * 1024,
                "0".repeat(64)));
  }
}
