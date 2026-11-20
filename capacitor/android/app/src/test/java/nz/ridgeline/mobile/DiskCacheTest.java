package nz.ridgeline.mobile;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.Test;

public class DiskCacheTest {
  @Test public void budgetHonoursTheOneGbSlider() {
    long total=DiskCacheBudget.total(1000, Long.MAX_VALUE);
    assertEquals(1_000_000_000L,total);
    assertEquals(0,DiskCacheBudget.total(0,Long.MAX_VALUE));
    assertEquals(1000,DiskCacheBudget.normalize(9999));
    assertEquals(0,DiskCacheBudget.normalize(-1));
    assertEquals(100_000_000L,DiskCacheBudget.total(1000,800_000_000L));
  }

  @Test public void realHttpCachePersistsEvictsLeastRecentlyUsedAndDisablesSeparately() throws Exception {
    File root=Files.createTempDirectory("ridgeline-disk-cache").toFile();
    File directory=new File(root,"http"),saved=new File(root,"saved-region.bin");
    Files.write(saved.toPath(),new byte[]{7,8,9});
    byte[] original=new byte[1024];Arrays.fill(original,(byte)37);
    MockWebServer server=new MockWebServer();
    server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){
      @Override public MockResponse dispatch(RecordedRequest r){return new MockResponse().setHeader("Cache-Control","max-age=3600").setBody(new Buffer().write(original));}
    });server.start();
    OkHttpClient client=TileHttpClient.create(directory,1_000_000L);
    try {
      get(client,server,"/a",original);client.cache().flush();
      long entry=client.cache().size();client.cache().close();
      client=TileHttpClient.create(directory,entry*2+100);
      get(client,server,"/a",original);assertEquals(1,server.getRequestCount());
      get(client,server,"/b",original);get(client,server,"/a",original);
      get(client,server,"/c",original);client.cache().flush();
      assertTrue(client.cache().size()<=client.cache().maxSize());
      get(client,server,"/a",original);assertEquals(3,server.getRequestCount());
      get(client,server,"/b",original);assertEquals(4,server.getRequestCount());
      client.cache().close();
      OkHttpClient disabled=TileHttpClient.create(directory,0);
      assertNull(disabled.cache());assertArrayEquals(new byte[]{7,8,9},Files.readAllBytes(saved.toPath()));
      assertEquals(0,directory.listFiles()==null?0:directory.listFiles().length);
      disabled.connectionPool().evictAll();disabled.dispatcher().executorService().shutdownNow();
    }finally{
      if(client.cache()!=null)client.cache().close();client.connectionPool().evictAll();client.dispatcher().executorService().shutdownNow();server.shutdown();
      for(File f:root.listFiles()){if(f.isDirectory())for(File child:f.listFiles())child.delete();f.delete();}root.delete();
    }
  }
  private void get(OkHttpClient c,MockWebServer server,String path,byte[] expected) throws IOException {
    try(Response r=c.newCall(new Request.Builder().url(server.url(path)).build()).execute()){assertTrue(r.isSuccessful());assertArrayEquals(expected,r.body().bytes());}
  }
}
