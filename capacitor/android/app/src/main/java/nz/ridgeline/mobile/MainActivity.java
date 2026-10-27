package nz.ridgeline.mobile;

import android.os.Bundle;
import android.view.View;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.*;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  private final TileRequestRegistry requests = new TileRequestRegistry();

  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(RidgelineOfflinePlugin.class);
    super.onCreate(savedInstanceState);
    if (bridge == null || bridge.getWebView() == null) return;
    View parent = (View) bridge.getWebView().getParent();
    ViewCompat.setOnApplyWindowInsetsListener(
        parent,
        (view, insets) -> {
          Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars()
                      | WindowInsetsCompat.Type.displayCutout()
                      | WindowInsetsCompat.Type.ime());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return WindowInsetsCompat.CONSUMED;
        });
    ViewCompat.requestApplyInsets(parent);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                bridge
                    .getWebView()
                    .evaluateJavascript(
                        "!!globalThis.RidgeMobile?.back()",
                        result -> {
                          if (!"true".equals(result)) moveTaskToBack(true);
                        });
              }
            });
  }

  @Override
  protected void load() {
    android.webkit.WebView view = findViewById(com.getcapacitor.android.R.id.webview);
    view.getSettings().setJavaScriptEnabled(true);
    view.addJavascriptInterface(requests, "RidgeTileControl");
    super.load();
    bridge.getWebView().setWebViewClient(new OfflineWebViewClient(bridge, requests));
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        && bridge != null
        && bridge.getWebView() != null)
      bridge.getWebView().evaluateJavascript("globalThis.__ridgeTransportInstalled?.trim()", null);
  }

  @Override
  public void onDestroy() {
    requests.close();
    if (bridge != null && bridge.getWebView() != null)
      bridge.getWebView().removeJavascriptInterface("RidgeTileControl");
    super.onDestroy();
  }
}
