package clockwork.web;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
public final class ItemCatalogChecks {
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static String token(String text){return text.isEmpty()?"_":Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));}
 static String route(int page,String mod,String query){return "/api/items/"+page+"/"+token(mod)+"/"+token(query);}
 static void rejects(ItemCatalog catalog,String path)throws Exception{try{catalog.page(path);throw new AssertionError("accepted invalid path "+path);}catch(IOException expected){}}
 public static void main(String[]args)throws Exception{
  var entries=new ArrayList<ItemCatalog.Entry>();for(int i=0;i<150;i++)entries.add(new ItemCatalog.Entry("minecraft:item_"+i,"Vanilla item "+i));
  entries.add(new ItemCatalog.Entry("steammod:brass_gear","Brass Gear"));entries.add(new ItemCatalog.Entry("othermod:brass_plate","Brass Plate"));
  var catalog=new ItemCatalog(entries);var first=catalog.page(route(0,"",""));require(first.getAsJsonArray("items").size()==64&&first.get("pages").getAsInt()==3,"bounded complete pagination");
  var ids=new HashSet<String>();for(int page=0;page<3;page++)for(var row:catalog.page(route(page,"","" )).getAsJsonArray("items"))ids.add(row.getAsJsonObject().get("id").getAsString());require(ids.size()==152&&ids.contains("steammod:brass_gear"),"all vanilla and modded IDs included");
  require(catalog.page(route(0,"","brass")).get("total").getAsInt()==2,"display-name search");require(catalog.page(route(0,"steammod","brass")).get("total").getAsInt()==1,"namespace filter");require(catalog.page(route(0,"","steammod:brass_gear")).get("total").getAsInt()==1,"namespaced ID search");
  rejects(catalog,route(3,"",""));rejects(catalog,"/api/items/000/_/_");rejects(catalog,"/api/items/0/_/eA==");rejects(catalog,"/api/items/0/_/wA");rejects(catalog,route(0,"","x".repeat(129)));rejects(catalog,route(0,"","\n"));
  var allowed=new AtomicBoolean(true);var access=new AccessSessions(owner->allowed.get());var files=new TemplateFiles(args.length>0?Path.of(args[0]):Files.createTempDirectory(Path.of("build"),"item-catalog-check-"),new TemplateJson());String id="a".repeat(64);
  var api=new TemplateApi("https://workshop.example",access,files,id,catalog);var anonymous=new Exchange(route(0,"",""));api.handle(anonymous);require(anonymous.code==401,"anonymous catalog denied");
  var owner=new AccessSessions.Owner(UUID.randomUUID(),"owner");var login=access.redeem(access.issue(owner));String cookie="__Host-clockwork_workshop_"+id+"="+login.token();
  var authenticated=new Exchange(route(0,"",""));authenticated.input.set("Cookie",cookie);api.handle(authenticated);require(authenticated.code==200,"authenticated catalog read");require(JsonParser.parseString(authenticated.output.toString(StandardCharsets.UTF_8)).getAsJsonObject().get("total").getAsInt()==152,"actual API catalog body");
  var status=new Exchange("/api/status");api.handle(status);require(JsonParser.parseString(status.output.toString(StandardCharsets.UTF_8)).getAsJsonObject().get("item_catalog").getAsBoolean(),"capability advertised");
  allowed.set(false);var revoked=new Exchange(route(0,"",""));revoked.input.set("Cookie",cookie);api.handle(revoked);require(revoked.code==401,"revoked owner catalog denied");
  require(RelayClient.routeAllowed("GET",route(0,"",""))&&!RelayClient.routeAllowed("PUT",route(0,"",""))&&!RelayClient.routeAllowed("HEAD",route(0,"","")),"read-only companion route");
  var empty = new ItemCatalog(List.of());require(empty.page(route(0,"","")).get("total").getAsInt()==0,"empty registry page");
  try{new ItemCatalog(Collections.nCopies(ItemCatalog.MAX_ITEMS+1,new ItemCatalog.Entry("minecraft:stone","Stone")));throw new AssertionError("oversized snapshot accepted");}catch(IOException expected){}
  var legacy=new TemplateApi("https://workshop.example",new AccessSessions(value->true),files,id);require(!legacy.itemCatalogAvailable(),"legacy constructor has no catalog capability");legacy.close();
  api.close();System.out.println("PASS: catalog paging/completeness, vanilla/modded names/IDs, namespace/search, encoding/limits, actual API anonymous/owner/revocation handling and read-only route. No sockets or server writes.");
 }
 static final class Exchange extends HttpExchange {
  final Headers input=new Headers(),headers=new Headers();final ByteArrayOutputStream output=new ByteArrayOutputStream();final URI uri;int code=-1;
  Exchange(String path){uri=URI.create(path);input.set("Host","workshop.example");}
  public Headers getRequestHeaders(){return input;}public Headers getResponseHeaders(){return headers;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return "GET";}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return InputStream.nullInputStream();}public OutputStream getResponseBody(){return output;}public void sendResponseHeaders(int status,long length){code=status;}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",0);}public int getResponseCode(){return code;}public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",0);}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String name){return null;}public void setAttribute(String name,Object value){}public void setStreams(InputStream in,OutputStream out){}public HttpPrincipal getPrincipal(){return null;}
 }
}
