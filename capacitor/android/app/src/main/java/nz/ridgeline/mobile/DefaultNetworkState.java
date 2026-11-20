package nz.ridgeline.mobile;

import java.util.Objects;

/** Default-network callbacks may arrive after a replacement network is available. */
final class DefaultNetworkState {
  private Object network;
  private volatile boolean connected;

  synchronized void available(Object next) {
    if (!Objects.equals(network, next)) {
      network = next;
      connected = false;
    }
  }

  synchronized void capabilities(Object source, boolean internet) {
    if (source != null && Objects.equals(network, source)) connected = internet;
  }

  synchronized void lost(Object source) {
    if (Objects.equals(network, source)) {
      network = null;
      connected = false;
    }
  }

  boolean connected() {
    return connected;
  }
}
