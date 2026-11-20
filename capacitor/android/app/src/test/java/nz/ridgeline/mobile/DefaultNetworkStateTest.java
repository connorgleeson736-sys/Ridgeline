package nz.ridgeline.mobile;

import static org.junit.Assert.*;
import org.junit.Test;

public class DefaultNetworkStateTest {
  @Test public void availabilityRequiresInternetCapabilityAndLossClearsIt() {
    var state = new DefaultNetworkState();
    assertFalse(state.connected());
    state.available("wifi"); assertFalse(state.connected());
    state.capabilities("wifi", true); assertTrue(state.connected());
    state.capabilities("wifi", false); assertFalse(state.connected());
    state.capabilities("wifi", true); state.lost("wifi"); assertFalse(state.connected());
  }

  @Test public void lateEventsFromTheOldDefaultCannotOverrideTheNewNetwork() {
    var state = new DefaultNetworkState();
    state.available("wifi"); state.capabilities("wifi", true);
    state.available("mobile"); assertFalse(state.connected());
    state.capabilities("wifi", true); assertFalse(state.connected());
    state.capabilities("mobile", true); state.lost("wifi"); assertTrue(state.connected());
    state.capabilities("wifi", false); assertTrue(state.connected());
    state.lost("mobile"); state.capabilities("mobile", true); assertFalse(state.connected());
  }

  @Test public void duplicateAvailabilityPreservesSeededCapabilitiesAndReadsAreStable() {
    var state = new DefaultNetworkState();
    state.available("wifi"); state.capabilities("wifi", true); state.available("wifi");
    for (int i = 0; i < 10000; i++) assertTrue(state.connected());
  }
}
