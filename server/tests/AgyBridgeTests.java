import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Uses a fake localhost OpenAI-compatible bridge: no real agy CLI or credentials. */
public class AgyBridgeTests {
    static int pass=0;
    static void ok(boolean condition,String description){if(!condition)throw new AssertionError(description);pass++;}
    static void output(HttpExchange h,int code,String content)throws IOException{
        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
        h.getResponseHeaders().set("Content-Type","application/json");
        h.sendResponseHeaders(code,bytes.length);
        try(OutputStream out=h.getResponseBody()){out.write(bytes);}
    }
    static Map<String,String> config(int port){return Map.of(
        "AGY_BRIDGE_ENABLED","1",
        "AGY_BRIDGE_URL","http://127.0.0.1:"+port+"/v1/chat/completions",
        "AGY_BRIDGE_MODEL","Gemini 3.5 Flash");}
    static void invalid(Map<String,String> map){boolean blocked=false;try{new AgyBridge(HttpClient.newHttpClient(),map);}catch(IllegalArgumentException e){blocked=true;}ok(blocked,"Reject unsafe or malformed bridge endpoint");}
    public static void main(String[] a)throws Exception {
        AtomicInteger calls=new AtomicInteger();
        AtomicReference<Map<String,Object>> payload=new AtomicReference<>();
        AtomicReference<String> auth=new AtomicReference<>();
        AtomicInteger responseStatus=new AtomicInteger(200);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",h->{
            calls.incrementAndGet();auth.set(h.getRequestHeaders().getFirst("Authorization"));
            try{payload.set(Json.object(Json.parse(new String(h.getRequestBody().readAllBytes(),StandardCharsets.UTF_8))));}
            catch(Exception e){payload.set(Map.of());}
            output(h,responseStatus.get(),responseStatus.get()==200?"{\"choices\":[{\"message\":{\"content\":\"You can review your request, then submit it to Accounts.\"}}]}":"{\"error\":\"down\"}");
        });
        server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));server.start();
        try {
            HttpClient http=HttpClient.newHttpClient();
            AgyBridge off=new AgyBridge(http,Map.of());
            ok(!off.answer("Hello","Local guidance",List.of()).success() && calls.get()==0,"Disabled bridge does not make calls");
            AgyBridge client=new AgyBridge(http,config(server.getAddress().getPort()));
            AgyBridge.Reply out=client.answer("Help me with exam fees","Please ask Accounts",List.of());
            ok(out.success()&&out.answer().contains("Accounts"),"Mock bridge AI response accepted");
            ok(out.source().contains("Antigravity"),"AI provider clearly labeled");
            ok(calls.get()==1,"Bridge called once");
            ok("Gemini 3.5 Flash".equals(payload.get().get("model")),"Model forwarded");
            ok(Boolean.FALSE.equals(payload.get().get("stream")),"Non-streaming response requested");
            ok(auth.get()==null,"No invented API authorization header");
            var messages=Json.objects(payload.get().get("messages"));
            String prompt=(String)messages.get(messages.size()-1).get("content");
            ok(prompt.contains("Never invent student attendance")&&prompt.contains("Help me with exam fees"),"Policy and question included in last user message for agy bridge");
            ok(!prompt.contains("AUTHORIZED LOCAL RECORDS"),"No record transmission when disabled");
            int n=calls.get();
            List<Map<String,Object>> protectedData=List.of(Map.of("studentId","PRIVATE","present",12));
            var privateResult=client.answer("Show my attendance","Attendance was 12 classes",protectedData);
            ok(!privateResult.success()&&calls.get()==n,"Private local records do not cross the bridge by default");
            ok(privateResult.source().contains("not transmitted"),"Local record restriction visible in UI");
            responseStatus.set(500);
            var fallback=client.answer("Need help","Known answer",List.of());
            ok(!fallback.success()&&fallback.answer().equals("Known answer"),"500 response falls back without hallucinating");
            ok(fallback.source().contains("HTTP 500"),"HTTP error labeled without leaking response body");
            Map<String,String> withKey=new HashMap<>(config(server.getAddress().getPort()));withKey.put("AGY_BRIDGE_API_KEY","test-key");
            responseStatus.set(200);new AgyBridge(http,withKey).answer("General help","Local",List.of());
            ok("Bearer test-key".equals(auth.get()),"Optional explicit API key forwarded");
            invalid(Map.of("AGY_BRIDGE_ENABLED","1","AGY_BRIDGE_URL","http://example.org/v1/chat/completions"));
            invalid(Map.of("AGY_BRIDGE_ENABLED","1","AGY_BRIDGE_URL","http://127.0.0.1:8000/api/bootstrap"));
            invalid(Map.of("AGY_BRIDGE_ENABLED","1","AGY_BRIDGE_URL","file:///etc/passwd"));
            // Full API integration, with in-memory fake Wi-Fi and isolated no-demo store.
            AtomicReference<String> ssid=new AtomicReference<>("VNRVJIET_E_5G");
            Path dir=Files.createTempDirectory("campusdesk-agy-test");
            try {
                CampusDesk app=new CampusDesk(dir.resolve("store.json"),new WifiGate("","VNRVJIET_E",ssid::get));
                app.agyBridge=client;
                HttpServer api=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
                api.createContext("/",app::handler);
                api.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));api.start();
                try {
                    URI base=URI.create("http://127.0.0.1:"+api.getAddress().getPort());
                    var init=http.send(HttpRequest.newBuilder(base.resolve("/api/bootstrap")).GET().build(),HttpResponse.BodyHandlers.ofString());
                    String cookie=init.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
                    var request=HttpRequest.newBuilder(base.resolve("/api/assistant")).header("Cookie",cookie).header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"My fee shows unpaid\"}")).build();
                    var result=http.send(request,HttpResponse.BodyHandlers.ofString());
                    ok(result.statusCode()==200,"AI through Java helpdesk API returns successfully");
                    var b=Json.object(Json.parse(result.body()));
                    ok(((String)b.get("answer")).contains("Accounts"),"Java API renders fake bridge AI answer");
                    ok(((String)b.get("agentProvider")).contains("Antigravity"),"Java API identifies bridge provider");
                    ok(b.get("ticketDraft")!=null,"AI reply preserves deterministic ticket draft");
                    ok(app.db.count("tickets")==0,"AI never auto-submits ticket");
                    ok(app.db.count("attendance")==0,"No fictional student records loaded");
                } finally {api.stop(0);}
            } finally {
                try(var paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}
            }
            System.out.println("PASSED "+pass+" Antigravity bridge assertions (mock HTTP, no live CLI)");
        } finally {server.stop(0);}
    }
}
