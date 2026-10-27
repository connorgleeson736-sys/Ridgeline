package nz.ridgeline.mobile;

import android.webkit.*;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

final class OfflineWebViewClient extends BridgeWebViewClient {
  private final TileGateway gateway;

  OfflineWebViewClient(Bridge bridge, TileRequestRegistry requests) {
    super(bridge);
    gateway = new TileGateway(bridge.getContext(), requests);
  }

  @Override
  public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
    WebResourceResponse response = gateway.intercept(request);
    return response == null ? super.shouldInterceptRequest(view, request) : response;
  }
}
