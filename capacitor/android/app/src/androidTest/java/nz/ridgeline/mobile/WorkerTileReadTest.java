package nz.ridgeline.mobile;

import static org.junit.Assert.*;

import android.content.Context;
import android.webkit.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;

/** Proves dedicated-worker fetches actually pass through Android interception. */
public class WorkerTileReadTest {
  public static final class ResultBridge {
    final CountDownLatch latch = new CountDownLatch(1);
    volatile String result;

    @JavascriptInterface
    public void result(String value) {
      result = value;
      latch.countDown();
    }
  }

  @Test
  public void workerReadsDownloadedElevationWithoutNetwork() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    TileArchive archive = TileArchive.get(context);
    boolean old = archive.offlineOnly();
    WebView[] web = {null};
    ResultBridge result = new ResultBridge();
    double[] bounds = {170.13, -43.60, 170.15, -43.58};
    String id =
        archive.create(
            new JSONObject()
                .put("name", "Worker fixture")
                .put("key", "test-only")
                .put("bounds", new JSONArray(bounds)),
            new TilePlan(bounds, 13, "aerial", false));
    try {
      String gen = archive.queue(id);
      assertTrue(archive.begin(id, gen));
      byte[] data = OfflineArchiveTest.image(0xff01adb0);
      archive.write(id, TileAddress.tile("elevation", 13, 7967, 5200), data, gen);
      archive.offlineOnly(true);
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () -> {
                WebView view = new WebView(context);
                web[0] = view;
                view.getSettings().setJavaScriptEnabled(true);
                view.addJavascriptInterface(result, "TestResult");
                TileGateway gateway = new TileGateway(context);
                view.setWebViewClient(
                    new WebViewClient() {
                      @Override
                      public WebResourceResponse shouldInterceptRequest(
                          WebView v, WebResourceRequest r) {
                        return gateway.intercept(r);
                      }
                    });
                String worker =
                    "fetch('https://localhost/_ridge/linz/v1/tiles/elevation/WebMercatorQuad/13/7967/5200.png?api=unused').then(function(r){if(!r.ok)throw"
                        + " Error(r.status);return"
                        + " r.arrayBuffer();}).then(function(b){postMessage(String(b.byteLength));}).catch(function(e){postMessage('ERROR:'+e.message);});";
                String html =
                    "<html><head><meta charset='utf-8'></head><body><script>var w=new"
                        + " Worker(URL.createObjectURL(new Blob(["
                        + JSONObject.quote(worker)
                        + "],{type:'text/javascript'})));w.onmessage=function(e){TestResult.result(e.data);w.terminate();};w.onerror=function(){TestResult.result('WORKER"
                        + " ERROR');};</script></body></html>";
                view.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null);
              });
      assertTrue("Worker should receive the saved tile", result.latch.await(90, TimeUnit.SECONDS));
      assertEquals(String.valueOf(data.length), result.result);
    } finally {
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () -> {
                if (web[0] != null) web[0].destroy();
              });
      archive.remove(id);
      archive.offlineOnly(old);
    }
  }
}
