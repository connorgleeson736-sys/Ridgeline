import nz.ridgeline.mobile.TileAddress;
public class SurveyProbe {
 public static void main(String[] names) {
  int accepted=0;
  for(String name:names){String url="https://localhost/_ridge/linz/v1/tiles/"+name+"/WebMercatorQuad/17/127471/83202.png?api=probe";TileAddress address=TileAddress.local(url);if(address!=null)accepted++;if(name.startsWith("canterbury-2015"))System.out.println("Cook survey accepted: "+(address!=null));}
  System.out.println("Accepted catalogue surveys: "+accepted+"/"+names.length);
 }
}
