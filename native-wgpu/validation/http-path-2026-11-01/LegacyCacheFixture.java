import java.net.*;
import java.nio.file.*;
import java.io.*;
import okhttp3.*;
public class LegacyCacheFixture {
 public static void main(String[] args) throws Exception {
  Path out=Path.of(args[0]);Files.createDirectories(out.resolve("cache"));
  byte[] body="Original HTTP bytes from OkHttp 4.12.0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  try(ServerSocket server=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"))){
   Thread worker=new Thread(()->{try(Socket socket=server.accept()){
    BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream()));reader.readLine();while(!reader.readLine().isEmpty()){}
    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nCache-Control: public, max-age=2147483647\r\nVary: Accept-Encoding, User-Agent\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes());socket.getOutputStream().write(body);socket.getOutputStream().flush();
   }catch(Exception e){throw new RuntimeException(e);}});worker.start();
   String url="http://127.0.0.1:"+server.getLocalPort()+"/legacy-tile";
   Cache cache=new Cache(out.resolve("cache").toFile(),1048576);
   OkHttpClient client=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cache(cache).build();
   try(Response response=client.newCall(new Request.Builder().url(url).header("User-Agent","Ridgeline-Native/0.1.0").build()).execute()){
    if(!java.util.Arrays.equals(body,response.body().bytes()))throw new AssertionError();
   }
   cache.close();client.dispatcher().executorService().shutdownNow();client.connectionPool().evictAll();worker.join();
   Files.writeString(out.resolve("fixture.json"),"{\"producer\":\"OkHttp 4.12.0\",\"url\":\""+url+"\",\"body\":\"Original HTTP bytes from OkHttp 4.12.0\"}\n");
  }
 }
}
