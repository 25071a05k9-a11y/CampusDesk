import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;

public final class CollegeWifiTests {
  private static int passed=0;
  static void ok(boolean condition,String why){if(!condition)throw new AssertionError(why);passed++;}
  static String safe(Object v){return v==null?"":v.toString();}
  static HttpRequest get(URI u,String cookie){return HttpRequest.newBuilder(u).header("Cookie",cookie==null?"":cookie).GET().build();}
  static HttpRequest post(URI u,String cookie,String json){return HttpRequest.newBuilder(u).header("Cookie",cookie==null?"":cookie).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();}
  static HttpResponse<String> send(HttpClient cli,HttpRequest request)throws Exception{return cli.send(request,HttpResponse.BodyHandlers.ofString());}
  static void del(Path dir)throws Exception{try(var s=Files.walk(dir)){s.sorted(Comparator.reverseOrder()).forEach(x->{try{Files.deleteIfExists(x);}catch(Exception ignored){}});}}
  public static void main(String[] args)throws Exception{
    AtomicReference<String> network=new AtomicReference<>("");
    WifiGate wifi=new WifiGate("","VNRVJIET_E",network::get);
    ok(!wifi.matches(""),"Empty Wi-Fi is denied");
    ok(!wifi.matches("HomeRouter"),"Different Wi-Fi denied");
    ok(wifi.matches("VNRVJIET_E"),"Prefix exact accepted");
    ok(wifi.matches("VNRVJIET_E_5G"),"College prefix extension accepted");
    ok(!wifi.matches("X_VNRVJIET_E"),"College Wi-Fi prefix cannot occur midway");
    WifiGate strict=new WifiGate("VNRVJIET_E_5G","VNRVJIET_E",network::get);
    ok(strict.matches("VNRVJIET_E_5G")&&!strict.matches("VNRVJIET_E_fake"),"Exact SSID preferred when configured");
    Path dir=Files.createTempDirectory("campusdesk-wifi-test");
    var app=new CampusDesk(dir.resolve("data.json"),wifi);
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/",app::handler);server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"wifi-tests");t.setDaemon(true);return t;}));server.start();
    URI root=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
    HttpClient cli=HttpClient.newHttpClient();
    try{
      var blocked=send(cli,get(root.resolve("/api/bootstrap"),null));
      ok(blocked.statusCode()==403,"Dashboard bootstrap denied away from campus Wi-Fi");
      ok(app.db.count("users")==0,"Denial does not create users");
      ok(send(cli,get(root.resolve("/"),null)).body().contains("Connect to college Wi-Fi"),"Lock screen is served without login");
      ok(send(cli,post(root.resolve("/api/tickets"),null,"{}" )).statusCode()==403,"Ticket writes blocked off Wi-Fi");
      network.set("VNRVJIET_E_5G");Thread.sleep(2100);
      var status=send(cli,get(root.resolve("/api/network/status"),null));
      ok(status.statusCode()==200&&status.body().contains("\"allowed\":true"),"Gate reflects college connection");
      var first=send(cli,get(root.resolve("/api/bootstrap"),null));
      ok(first.statusCode()==200,"Dashboard auto-opens on campus Wi-Fi");
      ok(first.body().contains("\"name\":\"Visitor\""),"No login, visitor workspace");
      ok(!Json.object(Json.parse(first.body())).containsKey("timetable"),"Timetable removed from dashboard API");
      var publicFaculty=Json.objects(Json.object(Json.parse(first.body())).get("faculty"));
      ok(publicFaculty.size()>=140,"Official faculty reference directory available without dummy seeds");
      ok(publicFaculty.stream().anyMatch(f->"Dr. Vadlana Baby".equals(f.get("name"))),"Official CSE faculty visible");
      ok(publicFaculty.stream().allMatch(f->safe(f.get("source")).startsWith("https://www.vnrvjiet.ac.in/departments/")),"Faculty source links are on official domain");
      ok(app.db.count("faculty")==0,"Public faculty reference is not synthetic database seeding");
      ok(app.db.count("users")==1&&app.db.count("tickets")==0,"No demo or seeded data created");
      String setCookie=first.headers().firstValue("Set-Cookie").orElseThrow();
      String cookie=setCookie.split(";",2)[0];
      var forbidden=send(cli,post(root.resolve("/api/auth/start"),cookie,"{}"));
      ok(forbidden.statusCode()==404,"Email OTP endpoint not available");
      var badAdmin=send(cli,get(root.resolve("/api/admin/analytics"),cookie));
      ok(badAdmin.statusCode()==403,"Visitor cannot open staff or admin data");
      var assist=send(cli,post(root.resolve("/api/assistant"),cookie,"{\"message\":\"My exam fee still shows pending\"}"));
      ok(assist.statusCode()==200,"AI helpdesk endpoint responds without a login");
      var ai=Json.object(Json.parse(assist.body()));
      var draft=Json.object(ai.get("ticketDraft"));
      ok("Examination Branch".equals(draft.get("department")),"Assistant routes exam payment mismatch appropriately");
      ok(app.db.count("tickets")==0,"Assistant does not create a ticket without visitor consent");
      ok(!CampusDesk.yes(ai.get("eduprimeLive")),"Bot does not claim it checked live EduPrime");
      ok(safe(ai.get("agentProvider")).contains("no external AI model"),"Local helper does not masquerade as external AI");
      var instructions=send(cli,post(root.resolve("/api/assistant"),cookie,"{\"message\":\"How do I create a complaint?\"}"));
      ok(instructions.statusCode()==200&&instructions.body().contains("New request"),"Bot explains how to create a request");
      // Regression coverage for reported AI assistant misrouting and misleading responses.
      var greeting=Json.object(Json.parse(send(cli,post(root.resolve("/api/assistant"),cookie,"{\"message\":\"Hello!\"}")).body()));
      ok(safe(greeting.get("answer")).contains("Hi!"),"Plain greeting gets a friendly response");
      var greetingIssue=Json.object(Json.parse(send(cli,post(root.resolve("/api/assistant"),cookie,"{\"message\":\"Hello, my exam fee is still pending\"}")).body()));
      ok(!safe(greetingIssue.get("answer")).startsWith("Hi!"),"Issue prefixed with hello does not get a greeting-only reply");
      ok("Examination Branch".equals(Json.object(greetingIssue.get("ticketDraft")).get("department")),"Greeting and issue creates an exam ticket draft");
      var mismatch=Json.object(Json.parse(send(cli,post(root.resolve("/api/assistant"),cookie,"{\"message\":\"I paid my fee but the portal says unpaid\"}")).body()));
      ok("Accounts".equals(Json.object(mismatch.get("ticketDraft")).get("department")),"Unpaid portal mismatch routes to Accounts");
      var wifiIssue=Json.object(Json.parse(send(cli,post(root.resolve("/api/assistant"),cookie,"{\"message\":\"The classroom WiFi is not working\"}")).body()));
      ok("Facilities".equals(Json.object(wifiIssue.get("ticketDraft")).get("department")),"Broken Wi-Fi is routed to Facilities, not answered as access help");
      var attendance=Json.object(Json.parse(send(cli,post(root.resolve("/api/assistant"),cookie,"{\"message\":\"What is my attendance?\"}")).body()));
      ok(safe(attendance.get("answer")).contains("No attendance records"),"No invented attendance numbers");
      var timetable=Json.object(Json.parse(send(cli,post(root.resolve("/api/assistant"),cookie,"{\"message\":\"Show my timetable\"}")).body()));
      ok(safe(timetable.get("answer")).contains("does not provide class timetables"),"Removed timetable feature cannot fabricate a schedule");
      var emptyRequests=Json.object(Json.parse(send(cli,post(root.resolve("/api/assistant"),cookie,"{\"message\":\"Check status of my requests\"}")).body()));
      ok(safe(emptyRequests.get("answer")).contains("No requests"),"Empty request status explained");
      var guide=Json.object(Json.parse(send(cli,post(root.resolve("/api/assistant"),cookie,"{\"message\":\"How do I submit a complaint?\"}")).body()));
      ok(guide.get("ticketDraft")==null,"How-to question does not draft an unsolicited ticket");
      ok(app.db.count("tickets")==0,"AI questions never auto-submit requests");
      var ticket=send(cli,post(root.resolve("/api/tickets"),cookie,"{\"subject\":\"Classroom projector\",\"description\":\"The projector does not turn on\",\"department\":\"Facilities\",\"priority\":\"medium\"}"));
      ok(ticket.statusCode()==201,"Visitor can create a real local ticket");
      ok(app.db.count("tickets")==1,"Only explicitly created requests are present");
      var disabledComment=send(cli,post(root.resolve("/api/tickets/"+Json.object(Json.parse(ticket.body())).get("id")+"/comment"),cookie,"{\"message\":\"hello\"}"));
      ok(disabledComment.statusCode()==404,"Ticket comments endpoint disabled");
      var disabledTimetableImport=send(cli,post(root.resolve("/api/admin/import"),cookie,"{\"type\":\"timetable\",\"records\":[]}"));
      ok(disabledTimetableImport.statusCode()==403,"Visitors cannot import timetables");
      var list=send(cli,get(root.resolve("/api/bootstrap"),cookie));
      ok(list.statusCode()==200&&list.body().contains("Classroom projector"),"Visitor can retrieve own ticket");
      var statusOwn=send(cli,post(root.resolve("/api/assistant"),cookie,"{\"message\":\"Check status of my ticket CD-"+Json.object(Json.parse(ticket.body())).get("id")+"\"}"));
      ok(statusOwn.body().contains("Classroom projector"),"Bot can report own ticket status");
      var second=send(cli,get(root.resolve("/api/bootstrap"),null));
      ok(second.statusCode()==200&&!second.body().contains("Classroom projector"),"Another browser cannot see previous tickets");
      String otherCookie=second.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
      var deniedChat=send(cli,post(root.resolve("/api/assistant"),otherCookie,"{\"message\":\"Status of my ticket CD-"+Json.object(Json.parse(ticket.body())).get("id")+"\"}"));
      ok(!deniedChat.body().contains("Classroom projector"),"Bot cannot expose another visitor's ticket");
      network.set("HomeRouter");Thread.sleep(2100);
      ok(send(cli,get(root.resolve("/api/bootstrap"),cookie)).statusCode()==403,"Wi-Fi disconnect revokes all API access");
      ok(send(cli,get(root.resolve("/"),cookie)).body().contains("Connect to college Wi-Fi"),"Disconnect locks page immediately on next navigation");
      Store reloaded=new Store(dir.resolve("data.json"));
      ok(reloaded.count("tickets")==1,"User-created tickets persist across restart");
      System.out.println("PASSED "+passed+" assertions; all test data created under temporary directory");
    }finally{server.stop(0);del(dir);}
  }
}
