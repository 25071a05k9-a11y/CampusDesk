import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.io.*;

/** CampusDesk Java 21 API. No seeded college data. Local prototype, NOT a production identity provider. */
public final class CampusDesk {
    final Store db;
    final List<Map<String,Object>> officialFaculty;
    final SecureRandom rng=new SecureRandom();
    final WifiGate wifi;
    final Map<String,Deque<Long>> requestLimits=new ConcurrentHashMap<>();
    final HttpClient external=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    AgyBridge agyBridge=AgyBridge.fromEnvironment(external);
    volatile long version=1;
    final ScheduledExecutorService emailWorker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"CampusDesk-mailer");t.setDaemon(true);return t;});
    final Path frontend=Path.of("frontend/dist");
    static final Set<String> DEPTS=Set.of("Subject Lecturer","Class Coordinator","Head of Department","Examination Branch","Accounts","Administration","Facilities","Student Affairs");
    static final Set<String> STATUSES=Set.of("open","in_progress","waiting_student","resolved","closed");
    static final Set<String> PRIORITIES=Set.of("low","medium","high","urgent");
    static final String PORTAL="https://vnrvjietexams.net/EduPrimeExam/EduPrime";
    static final class ApiException extends RuntimeException {final int status;ApiException(int s,String x){super(x);status=s;}}
    CampusDesk(Path data)throws Exception{this(data,WifiGate.fromEnvironment());}
    CampusDesk(Path data,WifiGate gate)throws Exception{db=new Store(data);wifi=gate;officialFaculty=loadOfficialFaculty();}
    static Map<String,Object> m(Object... kv){Map<String,Object> out=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)out.put(String.valueOf(kv[i]),kv[i+1]);return out;}
    static String s(Object o){return Json.s(o);}
    static String field(Map<String,Object> data,String key,int max){String x=s(data.get(key)).trim();if(x.length()>max)throw new ApiException(400,key+" is too long");return x;}
    static String required(Map<String,Object> data,String key,int max){String x=field(data,key,max);if(x.isEmpty())throw new ApiException(400,key+" is required");return x;}
    static boolean yes(Object v){return Boolean.TRUE.equals(v);}
    static String now(){return Instant.now().toString();}
    static long ms(){return System.currentTimeMillis();}
    static String email(String raw){String e=raw.trim().toLowerCase(Locale.ROOT);if(e.length()>254||!e.matches("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}$"))throw new ApiException(400,"Enter a valid email address");return e;}
    static String roll(String raw){String r=raw.trim().toUpperCase(Locale.ROOT).replace(" ","");if(!r.matches("[A-Z0-9]{6,20}"))throw new ApiException(400,"Roll number must contain 6–20 letters or digits");return r;}
    static String sha(String text){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(b);}catch(Exception e){throw new IllegalStateException(e);}}
    String randomToken(int n){byte[] b=new byte[n];rng.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    String randomCode(){return String.format("%06d",rng.nextInt(1_000_000));}
    static void check(boolean ok,int status,String message){if(!ok)throw new ApiException(status,message);}
    static boolean eq(Object a,Object b){return Objects.equals(s(a),s(b));}
    boolean staff(Map<String,Object> u){return "staff".equals(u.get("role"));}
    boolean admin(Map<String,Object> u){return staff(u)&&yes(u.get("admin"));}
    boolean onDept(Map<String,Object> u,String d){return staff(u)&&(admin(u)||eq(u.get("department"),d));}
    Map<String,Object> userById(String id){return db.find("users","id",id);}
    void bump(){version++;}
    synchronized void persist(){try{db.save();bump();}catch(Exception e){throw new ApiException(500,"Could not persist server data");}}
    String param(URI u,String key){String q=u.getRawQuery();if(q==null)return "";for(String p:q.split("&",-1)){String[] z=p.split("=",2);if(z[0].equals(key))return URLDecoder.decode(z.length>1?z[1]:"",StandardCharsets.UTF_8);}return "";}
    Map<String,Object> account(Map<String,Object> u){return m("id",u.get("id"),"name",u.get("name"),"email",u.get("email"),"role",u.get("role"),"admin",u.getOrDefault("admin",false),"department",u.getOrDefault("department",""));}
    void notifyUser(String userId,String title,String message,String ticketId){
        db.rows("notifications").add(m("id",db.id(),"userId",userId,"title",title,"message",message,"ticketId",ticketId,"at",now(),"read",false));
        if(false && Smtp.configured()){ // No email or OTP in the no-login edition.
            var person=userById(userId);
            if(person!=null)db.rows("emailOutbox").add(m("id",db.id(),"to",person.get("email"),"subject","CampusDesk: "+title,"body",message+(ticketId.isBlank()?"":"\nTicket: CD-"+ticketId),"state","pending","attempts",0,"nextAt",ms()));
        }
    }
    void sendOutbox(){
        if(!Smtp.configured())return;
        Map<String,Object> target=null;
        synchronized(db){for(var row:db.rows("emailOutbox")){
            if(eq(row.get("state"),"pending")&&Long.parseLong(s(row.get("nextAt")))<=ms()) {target=new LinkedHashMap<>(row);break;}
        }}
        if(target==null)return;
        boolean sent=false;try{Smtp.send(s(target.get("to")),s(target.get("subject")),s(target.get("body")));sent=true;}catch(Exception ex){System.err.println("Ticket notification delivery failed; retry queued: "+ex.getClass().getSimpleName());}
        synchronized(db){var row=db.find("emailOutbox","id",s(target.get("id")));if(row!=null&&eq(row.get("state"),"pending")){
            int count=((Number)row.get("attempts")).intValue()+1;
            row.put("attempts",count);row.put("state",sent?"sent":count>=4?"failed":"pending");row.put("nextAt",ms()+Math.min(900000,60000L*count));row.put("updatedAt",now());persist();
        }}
    }
    void notifyDept(String dept,String title,String message,String ticketId){for(var u:db.rows("users"))if(onDept(u,dept))notifyUser(s(u.get("id")),title,message,ticketId);}
    boolean accessTicket(Map<String,Object> u,Map<String,Object> ticket){return admin(u)||eq(ticket.get("studentId"),u.get("id"))||onDept(u,s(ticket.get("department")));}
    boolean canManage(Map<String,Object> u,Map<String,Object> ticket){return onDept(u,s(ticket.get("department")));}
    Map<String,Object> ticketOrFail(Map<String,Object> u,String id){var t=db.find("tickets","id",id);check(t!=null,404,"Ticket not found");check(accessTicket(u,t),403,"Ticket access denied");return t;}
    List<Map<String,Object>> ticketEvents(Map<String,Object> u,String id){List<Map<String,Object>> result=new ArrayList<>();for(var event:db.rows("events"))if(eq(event.get("ticketId"),id)&&!Set.of("comment","staff_note").contains(s(event.get("kind")))&&(!"private".equals(event.get("visibility"))||staff(u)))result.add(new LinkedHashMap<>(event));return result;}
    Map<String,Object> fullTicket(Map<String,Object> u,Map<String,Object> t){Map<String,Object> ret=new LinkedHashMap<>(t);ret.put("events",ticketEvents(u,s(t.get("id"))));ret.put("overdue",!"closed".equals(t.get("status"))&&!"resolved".equals(t.get("status"))&&Instant.parse(s(t.get("dueAt"))).isBefore(Instant.now()));ret.put("attachments",db.rows("attachments").stream().filter(a->eq(a.get("ticketId"),t.get("id"))).map(a->m("id",a.get("id"),"name",a.get("name"),"type",a.get("type"),"size",a.get("size"))).toList());return ret;}
    void addEvent(String id,String user,String kind,String body,String visibility){db.rows("events").add(m("id",db.id(),"ticketId",id,"authorId",user,"kind",kind,"body",body,"visibility",visibility,"at",now()));}
    void rate(String key,int limit,long windowMs){Deque<Long> q=requestLimits.computeIfAbsent(key,k->new ArrayDeque<>());synchronized(q){long t=ms();while(!q.isEmpty()&&q.peek()<t-windowMs)q.removeFirst();check(q.size()<limit,429,"Too many requests. Try again later.");q.addLast(t);}}
    // Anonymous browser workspaces replace email/password/OTP authentication entirely.
    // Visitors can see ONLY records they created on this browser; staff roles are inaccessible.
    Map<String,Object> sessionUser(HttpExchange h){
        String cookie=h.getRequestHeaders().getFirst("Cookie");
        if(cookie==null)return null;
        for(String part:cookie.split(";")){
            String[] pair=part.trim().split("=",2);
            if(pair.length!=2||!pair[0].equals("campusdesk_guest"))continue;
            synchronized(db){
                Map<String,Object> stored=db.find("guestSessions","tokenHash",sha(pair[1]));
                if(stored==null)return null;
                try{if(Instant.parse(s(stored.get("expiresAt"))).isBefore(Instant.now()))return null;}
                catch(Exception e){return null;}
                Map<String,Object> u=userById(s(stored.get("userId")));
                return u!=null&&yes(u.get("visitor"))&&!staff(u)?u:null;
            }
        }
        return null;
    }
    Map<String,Object> require(HttpExchange h){
        check(wifi.allowed(),403,"Connect to VNRVJIET college Wi-Fi to use CampusDesk.");
        Map<String,Object> existing=sessionUser(h);
        if(existing!=null)return existing;
        synchronized(db){
            String userId=db.id(),token=randomToken(32);
            Map<String,Object> guest=m("id",userId,"name","Visitor","email","","role","student", "department","", "admin",false,"visitor",true,"createdAt",now());
            db.rows("users").add(guest);
            db.rows("guestSessions").add(m("userId",userId,"tokenHash",sha(token),"expiresAt",Instant.now().plus(Duration.ofDays(90)).toString()));
            persist();
            h.getResponseHeaders().add("Set-Cookie","campusdesk_guest="+token+"; HttpOnly; SameSite=Strict; Path=/; Max-Age=7776000");
            return guest;
        }
    }
    static Map<String,Object> body(HttpExchange h)throws Exception{int max=1_500_000;ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=h.getRequestBody()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(b.size()+n>max)throw new ApiException(413,"Request exceeds size limit");b.write(buf,0,n);}}return Json.object(Json.parse(b.toString(StandardCharsets.UTF_8)));}
    static void respond(HttpExchange h,int code,Object obj)throws IOException{byte[] bytes=Json.stringify(obj).getBytes(StandardCharsets.UTF_8);h.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");h.getResponseHeaders().set("Cache-Control","no-store");h.getResponseHeaders().set("X-Content-Type-Options","nosniff");h.getResponseHeaders().set("Referrer-Policy","no-referrer");h.sendResponseHeaders(code,bytes.length);try(OutputStream out=h.getResponseBody()){out.write(bytes);}}
    static void sendBytes(HttpExchange h,int code,String type,byte[] data)throws IOException{h.getResponseHeaders().set("Content-Type",type);h.getResponseHeaders().set("X-Content-Type-Options","nosniff");h.getResponseHeaders().set("Cache-Control","no-store");h.sendResponseHeaders(code,data.length);try(OutputStream out=h.getResponseBody()){out.write(data);}}
    Map<String,Object> config(){return m("application","CampusDesk","college","VNRVJIET","eduprimeUrl",PORTAL,"emailConfigured",false,"jevConfigured",!Smtp.env("JEV_API_KEY").isEmpty(),"agentConfigured",!Smtp.env("AGENT_API_KEY").isEmpty()&&!Smtp.env("AGENT_API_URL").isEmpty(),"agyCliConfigured",AgyCli.enabled(),"agyHttpConfigured",agyBridge.enabled,"chatEngine",AgyCli.enabled()?"AGY CLI":agyBridge.enabled?"AGY HTTP bridge":"Local guidance (configure AGY)","dataMode","Only user-created or administrator-imported records; no EduPrime live connection","server","Java 21","frontend","React","access","Local college Wi-Fi only; no user identity verification");}
    void checkOverdue(){
        synchronized(db){boolean changed=false;for(var t:db.rows("tickets")){
            if(Set.of("closed","resolved").contains(t.get("status"))||yes(t.get("escalationNotified")))continue;
            try{if(Instant.parse(s(t.get("dueAt"))).isAfter(Instant.now()))continue;}catch(Exception e){continue;}
            t.put("escalationNotified",true);String ticketId=s(t.get("id"));
            notifyDept(s(t.get("department")),"Overdue request",s(t.get("subject")),ticketId);
            notifyUser(s(t.get("studentId")),"Request needs attention","Your ticket passed its target response time",ticketId);
            db.audit("system","sla_overdue",ticketId);changed=true;
        }if(changed)persist();}
    }
    Object bootstrap(Map<String,Object> u){
        checkOverdue();
        synchronized(db){
            String uid=s(u.get("id"));List<Map<String,Object>> tickets=new ArrayList<>(),notes=new ArrayList<>();
            for(var t:db.rows("tickets"))if(accessTicket(u,t))tickets.add(fullTicket(u,t));
            for(var n:db.rows("notifications"))if(eq(n.get("userId"),uid))notes.add(new LinkedHashMap<>(n));
            Collections.reverse(tickets);Collections.reverse(notes);
            Map<String,Object> claim=db.find("claims","userId",uid);
            List<Map<String,Object>> claims=new ArrayList<>();if(admin(u))for(var c:db.rows("claims"))if(eq(c.get("status"),"pending")){Map<String,Object> cr=new LinkedHashMap<>(c);var owner=userById(s(c.get("userId")));cr.put("email",owner==null?"":owner.get("email"));cr.put("name",owner==null?"":owner.get("name"));claims.add(cr);}
            List<Map<String,Object>> articles=db.rows("articles").stream().map(LinkedHashMap::new).map(x->(Map<String,Object>)x).toList();
            List<Map<String,Object>> att=db.rows("attendance").stream().filter(x->eq(x.get("studentId"),uid)).map(x->(Map<String,Object>)new LinkedHashMap<>(x)).toList();
            List<Map<String,Object>> payments=db.rows("payments").stream().filter(x->eq(x.get("studentId"),uid)).map(x->(Map<String,Object>)new LinkedHashMap<>(x)).toList();
            List<Map<String,Object>> staffNames=new ArrayList<>();for(var x:db.rows("users"))if(staff(x))staffNames.add(m("id",x.get("id"),"name",x.get("name"),"department",x.get("department")));
            List<Map<String,Object>> pendingApprovals=new ArrayList<>();if(staff(u))for(var a:db.rows("approvals"))if(eq(a.get("status"),"pending")&&onDept(u,s(a.get("department")))&&!eq(a.get("initiator"),u.get("id"))){var row=new LinkedHashMap<>(a);var ticket=db.find("tickets","id",s(a.get("ticketId")));row.put("subject",ticket==null?"":ticket.get("subject"));pendingApprovals.add(row);}
            return m("user",account(u),"claim",claim==null?null:new LinkedHashMap<>(claim),"tickets",tickets,"notifications",notes,"pendingClaims",claims,"articles",articles,"attendance",att,"payments",payments,"faculty",facultyDirectory(),"staff",staffNames,"pendingApprovals",pendingApprovals,"version",version);
        }
    }
    static List<Map<String,Object>> loadOfficialFaculty(){
        Path file=Path.of("reference/faculty-vnrvjiet.json");
        if(!Files.isRegularFile(file))return List.of();
        try{
            List<Map<String,Object>> verified=new ArrayList<>();
            for(Map<String,Object> row:Json.objects(Json.parse(Files.readString(file,StandardCharsets.UTF_8)))){
                String name=s(row.get("name")).trim(), dept=s(row.get("department")).trim(), source=s(row.get("source")).trim();
                if(name.isEmpty()||dept.isEmpty()||!source.startsWith("https://www.vnrvjiet.ac.in/departments/"))continue;
                verified.add(m("id",s(row.get("id")),"name",name,"department",dept,"source",source,"sourceType","official","checkedOn",s(row.get("checkedOn"))));
            }
            return Collections.unmodifiableList(verified);
        }catch(Exception e){System.err.println("Official faculty reference could not be read: "+e.getMessage());return List.of();}
    }
    List<Map<String,Object>> facultyDirectory(){
        Map<String,Map<String,Object>> merged=new LinkedHashMap<>();
        for(var record:officialFaculty){String key=(s(record.get("name"))+"|"+s(record.get("department"))).toLowerCase(Locale.ROOT);merged.put(key,new LinkedHashMap<>(record));}
        synchronized(db){for(var record:db.rows("faculty")){
            String key=(s(record.get("name"))+"|"+s(record.get("department"))).toLowerCase(Locale.ROOT);
            var local=new LinkedHashMap<String,Object>(record);local.put("sourceType","imported");
            merged.put(key,local);
        }}
        return new ArrayList<>(merged.values());
    }
    String department(String raw){check(DEPTS.contains(raw),400,"Select a supported department");return raw;}
    String priority(String raw){check(PRIORITIES.contains(raw),400,"Invalid priority");return raw;}
    String dueFor(String p){long h=switch(p){case "urgent"->4;case "high"->24;case "low"->168;default->72;};return Instant.now().plus(Duration.ofHours(h)).toString();}
    Object newTicket(Map<String,Object> u,Map<String,Object> b){String subject=required(b,"subject",140),desc=required(b,"description",6000),dept=department(required(b,"department",70)),prio=priority(field(b,"priority",12).isEmpty()?"medium":field(b,"priority",12));
        synchronized(db){String id=db.id();Map<String,Object> t=m("id",id,"studentId",u.get("id"),"subject",subject,"description",desc,"department",dept,"status","open","priority",prio,"assignee","","createdAt",now(),"updatedAt",now(),"dueAt",dueFor(prio));db.rows("tickets").add(t);addEvent(id,s(u.get("id")),"created","Request submitted","public");notifyDept(dept,"New helpdesk request",subject,id);db.audit(s(u.get("id")),"ticket_created",id);persist();return fullTicket(u,t);}
    }
    Object manage(Map<String,Object> u,String id,Map<String,Object> b){synchronized(db){var t=ticketOrFail(u,id);check(canManage(u,t),403,"Assigned department staff required");String status=field(b,"status",32),dept=field(b,"department",70),p=field(b,"priority",12),assignee=field(b,"assignee",50);String before=s(t.get("department"));
        if(!status.isBlank()){check(STATUSES.contains(status),400,"Invalid status");if(status.equals("closed")&&eq(t.get("priority"),"urgent")){boolean approved=false;for(var a:db.rows("approvals"))if(eq(a.get("ticketId"),id)&&eq(a.get("status"),"approved"))approved=true;check(approved,409,"Urgent ticket closure requires approval by a different staff member");}t.put("status",status);}
        if(!dept.isBlank())t.put("department",department(dept));
        if(!p.isBlank()){t.put("priority",priority(p));t.put("dueAt",dueFor(p));}
        if(b.containsKey("assignee")){if(!assignee.isEmpty()){var person=userById(assignee);check(person!=null&&staff(person)&&onDept(person,s(t.get("department"))),400,"Assignee must belong to the receiving department");}t.put("assignee",assignee);}
        t.put("updatedAt",now());addEvent(id,s(u.get("id")),"updated","Status: "+t.get("status")+" · Department: "+t.get("department")+" · Priority: "+t.get("priority"),"public");
        notifyUser(s(t.get("studentId")),"Request updated",s(t.get("subject")),id);
        if(!before.equals(t.get("department")))notifyDept(s(t.get("department")),"Request transferred",s(t.get("subject")),id);
        db.audit(s(u.get("id")),"ticket_updated",id);persist();return fullTicket(u,t);}}
    Object requestApproval(Map<String,Object> u,String id){synchronized(db){var t=ticketOrFail(u,id);check(canManage(u,t),403,"Staff access required");check(eq(t.get("priority"),"urgent"),400,"Two-person approval is for urgent tickets");for(var a:db.rows("approvals"))if(eq(a.get("ticketId"),id)&&eq(a.get("status"),"pending"))return a;
        var a=m("id",db.id(),"ticketId",id,"initiator",u.get("id"),"status","pending","department",t.get("department"),"createdAt",now());db.rows("approvals").add(a);db.audit(s(u.get("id")),"closure_approval_requested",id);persist();return a;}}
    Object approve(Map<String,Object> u,String id,Map<String,Object> b){synchronized(db){Map<String,Object> a=db.find("approvals","id",id);check(a!=null,404,"Approval not found");check(onDept(u,s(a.get("department"))),403,"Department staff required");check(!eq(a.get("initiator"),u.get("id")),403,"A second staff member must review");check(eq(a.get("status"),"pending"),409,"Approval already reviewed");boolean accepted=yes(b.get("approve"));a.put("status",accepted?"approved":"rejected");a.put("reviewedBy",u.get("id"));a.put("reviewedAt",now());db.audit(s(u.get("id")),"closure_approval_reviewed",id);persist();return new LinkedHashMap<>(a);}}
    Object claim(Map<String,Object> u,Map<String,Object> b){check(!staff(u),403,"Student account required");String number=roll(required(b,"rollNumber",20));synchronized(db){for(var c:db.rows("claims"))if(!eq(c.get("userId"),u.get("id"))&&eq(c.get("rollNumber"),number)&&!eq(c.get("status"),"rejected"))throw new ApiException(409,"Roll number already claimed. Contact an administrator.");var c=db.find("claims","userId",s(u.get("id")));if(c==null){c=m("id",db.id(),"userId",u.get("id"));db.rows("claims").add(c);}c.put("rollNumber",number);c.put("status","pending");c.put("claimedAt",now());c.remove("verifiedAt");c.remove("reviewNote");db.audit(s(u.get("id")),"roll_claimed",number);persist();return new LinkedHashMap<>(c);}}
    Object reviewClaim(Map<String,Object> u,Map<String,Object> b){check(admin(u),403,"Administrator required");String userId=required(b,"userId",40),note=required(b,"note",500);check(note.length()>=8,400,"Provide an independent verification note of at least eight characters");synchronized(db){var c=db.find("claims","userId",userId);check(c!=null&&eq(c.get("status"),"pending"),404,"No pending claim");c.put("status",yes(b.get("approve"))?"verified":"rejected");c.put("verifiedAt",now());c.put("verifiedBy",u.get("id"));c.put("reviewNote",note);notifyUser(userId,"EduPrime roll claim reviewed","Verification status: "+c.get("status"),"");db.audit(s(u.get("id")),"roll_claim_review",userId+":"+c.get("status"));persist();return new LinkedHashMap<>(c);}}
    Object createStaff(Map<String,Object> u,Map<String,Object> b){check(admin(u),403,"Administrator required");String e=email(required(b,"email",254)),name=required(b,"name",80),dept=department(required(b,"department",70));synchronized(db){check(db.find("users","email",e)==null,409,"An account already exists with that email");var row=m("id",db.id(),"name",name,"email",e,"role","staff","department",dept,"admin",false,"createdAt",now());db.rows("users").add(row);db.audit(s(u.get("id")),"staff_created",e);persist();return account(row);}}
    Object addArticle(Map<String,Object> u,Map<String,Object> b){check(admin(u),403,"Administrator required");String title=required(b,"title",180),content=required(b,"content",7500),category=required(b,"category",50);synchronized(db){var a=m("id",db.id(),"title",title,"content",content,"category",category,"createdAt",now(),"author",u.get("id"));db.rows("articles").add(a);db.audit(s(u.get("id")),"article_created",s(a.get("id")));persist();return a;}}
    Object importRows(Map<String,Object> u,Map<String,Object> b){check(admin(u),403,"Administrator required");String type=required(b,"type",30);check(Set.of("attendance","payments","faculty","subjects").contains(type),400,"Unsupported import type");List<Map<String,Object>> items=Json.objects(b.get("records"));check(items.size()<=500,400,"Maximum 500 records per import");
        synchronized(db){List<Map<String,Object>> validated=new ArrayList<>();for(var row:items){Map<String,Object> record=new LinkedHashMap<>();String identifier="";
            switch(type){
              case "attendance"->{String subject=required(row,"subject",100),roll=roll(required(row,"rollNumber",20));var verified=db.rows("claims").stream().filter(c->eq(c.get("rollNumber"),roll)&&eq(c.get("status"),"verified")).findFirst().orElse(null);check(verified!=null,400,"No verified account for roll "+roll);int present=intIn(row,"present",0,1000_000),total=intIn(row,"total",0,1000_000);check(present<=total,400,"Present cannot exceed total");record=m("studentId",verified.get("userId"),"subject",subject,"present",present,"total",total,"updatedAt",now());identifier=s(verified.get("userId"))+"/"+subject;}
              case "payments"->{String roll=roll(required(row,"rollNumber",20));var verified=db.rows("claims").stream().filter(c->eq(c.get("rollNumber"),roll)&&eq(c.get("status"),"verified")).findFirst().orElse(null);check(verified!=null,400,"No verified account for roll "+roll);String label=required(row,"label",150),paymentId=required(row,"paymentId",80),paymentStatus=required(row,"paymentStatus",25);check(Set.of("paid","pending","failed","refunded").contains(paymentStatus),400,"Invalid payment status");record=m("studentId",verified.get("userId"),"label",label,"paymentId",paymentId,"paymentStatus",paymentStatus,"portalStatus",field(row,"portalStatus",40),"updatedAt",now());identifier=paymentId;}
              case "faculty"->{identifier=required(row,"id",50);record=m("id",identifier,"name",required(row,"name",120),"department",department(required(row,"department",70)),"office",field(row,"office",100),"email",field(row,"email",254));}
              case "subjects"->{identifier=required(row,"code",30);record=m("code",identifier,"name",required(row,"name",120),"facultyId",field(row,"facultyId",50));}
            }
            record.put("_key",identifier);validated.add(record);
        }
        // Only commit after all rows validate, avoiding partially accepted batches.
        List<Map<String,Object>> existing=db.rows(type);for(var r:validated){var prev=db.find(type,"_key",s(r.get("_key")));if(prev!=null){prev.clear();prev.putAll(r);}else existing.add(r);}db.audit(s(u.get("id")),"offline_import",type+":"+validated.size());persist();return m("imported",validated.size(),"type",type);
        }
    }
    static int intIn(Map<String,Object> b,String key,int low,int high){try{int n=Integer.parseInt(s(b.get(key)));check(n>=low&&n<=high,400,"Invalid "+key);return n;}catch(NumberFormatException e){throw new ApiException(400,"Invalid "+key);}}
    Object upload(Map<String,Object> u,String id,Map<String,Object> b){synchronized(db){var t=ticketOrFail(u,id);String file=required(b,"name",130);check(!file.contains("/")&&!file.contains("\\"),400,"Invalid filename");String type=required(b,"type",60);check(Set.of("application/pdf","image/png","image/jpeg","text/plain").contains(type),400,"Unsupported attachment type");String raw=required(b,"data",800_000);byte[] data;try{data=Base64.getDecoder().decode(raw);}catch(Exception e){throw new ApiException(400,"Invalid attachment");}check(data.length<=450_000,413,"Maximum attachment size is 450 KB");String attId=db.id();db.rows("attachments").add(m("id",attId,"ticketId",id,"name",file,"type",type,"data",raw,"size",data.length,"by",u.get("id")));addEvent(id,s(u.get("id")),"attachment","Attached "+file,"public");persist();return m("id",attId,"name",file,"type",type,"size",data.length);}}
    Object analytics(Map<String,Object> u){check(admin(u),403,"Administrator required");synchronized(db){Map<String,Integer> status=new TreeMap<>(),department=new TreeMap<>();int overdue=0;for(var t:db.rows("tickets")){status.merge(s(t.get("status")),1,Integer::sum);department.merge(s(t.get("department")),1,Integer::sum);if(!Set.of("closed","resolved").contains(t.get("status"))&&Instant.parse(s(t.get("dueAt"))).isBefore(Instant.now()))overdue++;}return m("total",db.count("tickets"),"byStatus",status,"byDepartment",department,"overdue",overdue,"students",db.rows("users").stream().filter(x->eq(x.get("role"),"student")).count(),"staff",db.rows("users").stream().filter(x->eq(x.get("role"),"staff")).count());}}
    Map<String,Object> route(String text){Map<String,Object> local=ruleRoute(text);String key=Smtp.env("JEV_API_KEY");if(key.isBlank())return local;
        var choices=m("database_lookup","Read a student-owned record or college knowledge article, without taking an action.","agent_investigation","Investigate a complicated question using authorized read-only context.","send_to_person","Issue requiring action from a specific person or college department.","ask_clarification","Insufficient detail for reliable routing.");
        var dest=m("lecturer","Subject Lecturer","coordinator","Class Coordinator","hod","Head of Department","exam","Examination Branch","accounts","Accounts","administration","Administration","facilities","Facilities","affairs","Student Affairs");
        var req=m("model",Smtp.env("JEV_MODEL").isBlank()?"jev-latest":Smtp.env("JEV_MODEL"),"state",m("request",text,"purpose","College helpdesk routing; no live EduPrime access"),"questions",m("path",m("type","choice","instructions","Select the next processing step","criteria",choices),"department",m("type","choice","instructions","Which department should handle actionable request?","criteria",dest)));
        try{HttpRequest request=HttpRequest.newBuilder(URI.create(Smtp.env("JEV_API_URL").isBlank()?"https://api.typesafe.ai/v1/systemone":Smtp.env("JEV_API_URL"))).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(req))).build();var res=external.send(request,HttpResponse.BodyHandlers.ofString());if(res.statusCode()!=200)throw new IllegalStateException("Jev HTTP "+res.statusCode());var answers=Json.object(Json.object(Json.parse(res.body())).get("answers"));var path=Json.object(answers.get("path"));var department=Json.object(answers.get("department"));String p=s(path.get("choice"));double confidence=Double.parseDouble(s(path.get("confidence")));if(!choices.containsKey(p)||confidence<0.65)return m("path","ask_clarification","department","","provider","Jev: uncertain","confidence",confidence,"reason","Please clarify the question");String dept=s(dest.get(s(department.get("choice"))));if(!p.equals("send_to_person")&&!p.equals("agent_investigation"))dept="";if(dept.isBlank()&&(p.equals("send_to_person")||p.equals("agent_investigation")))dept="Administration";return m("path",p,"department",dept,"provider","Jev API","confidence",confidence,"reason","Typed Jev decision");
        }catch(Exception e){local.put("provider","Local rules (Jev unavailable)");return local;}
    }
    // Keep local routing deterministic and explicit: this fallback is not a generative model.
    static Map<String,Object> ruleRoute(String raw){
        String q=raw.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").trim();
        String path="ask_clarification",dept="",reason="Please describe the question or problem.";
        boolean tracking=q.matches("(?s).*(my tickets?|my requests?|ticket status|request status|track (a |my )?(ticket|request)|cd-[0-9]+).*"
        );
        boolean procedure=q.matches("(?s).*(how (do|can|to)|steps to|where (do|can) i).*(create|submit|raise|report|make).*(ticket|request|complaint).*"
        );
        boolean actionable=q.matches("(?s).*(not updated|not reflected|still.*pending|shows? pending|unpaid|not working|didn.t|incorrect|mismatch|complain|complaint|broken|refund|failed|lost|issue with|problem with|unable to|doesn.t work|missing marks|not received|not showing|charged twice|double charged|payment successful but).*"
        );
        boolean explicitTicket=q.matches("(?s).*(raise (a )?ticket|raise (a )?complaint|report a |report an |submit (a )?complaint|need help with).*"
        );
        // A question about the process is not itself a complaint to be submitted.
        if(procedure){path="database_lookup";reason="Explain the CampusDesk request process.";}
        else if(tracking){path="database_lookup";reason="Look up this browser workspace's requests.";}
        else if(actionable||explicitTicket){
            path=actionable?"agent_investigation":"send_to_person";
            reason="This issue may require staff review; no ticket is created automatically.";
            dept=departmentFor(q);
        }else if(q.matches("(?s).*(attendance|timetable|schedule|faculty|lecturer|where is|room|payment|exam fee|fee status|my fee|how do|how to|certificate|what is|knowledge|eduprime|hello|hi|hey|help|marks|results|grades|courses).*")){
            path="database_lookup";reason="Check permitted local information and built-in help instructions.";
        }
        return m("path",path,"department",dept,"provider","Local guided routing (not Jev AI)","confidence",null,"reason",reason);
    }
    static String departmentFor(String q){
        // Exam-registration mismatches go to the Examination Branch; ordinary fees go to Accounts.
        if(q.matches("(?s).*(exam|hall ticket|marks|grade|result|registration).*"))return "Examination Branch";
        if(q.matches("(?s).*(fee|payment|refund|receipt|scholarship|transaction).*"))return "Accounts";
        if(q.matches("(?s).*(projector|water|classroom|room|electric|light|clean|facility|internet|wi-?fi|fan).*"))return "Facilities";
        if(q.matches("(?s).*(lecturer|professor|teacher|subject faculty).*"))return "Subject Lecturer";
        if(q.matches("(?s).*(hod|head of department).*"))return "Head of Department";
        return "Administration";
    }
    static String platformHelp(String q){
        if(q.matches("(?s)^(hello|hi|hey|help|hello there|hi there|what can you do)[!?. ]*$"))
            return "Hi! I can explain CampusDesk procedures, check available local records, show your requests, and prepare a request for the appropriate department. I cannot access live EduPrime data or contact staff automatically.";
        if(q.matches("(?s).*(how (do|can|to)|steps to|where (do|can) i).*(create|submit|raise|report|make).*(ticket|request|complaint).*"))
            return "Open 'New request', enter a subject and description, choose a department, and submit. Or describe the problem here: I can prepare an editable ticket draft. Your tickets are tracked in 'My requests'; submission stores them locally, not in the college's official system.";
        if(q.contains("eduprime"))
            return "CampusDesk is not connected to EduPrime records. Open the official portal at "+PORTAL+". I can't verify or change your marks, attendance or exam fees there.";
        if(q.contains("wifi")&&!q.matches("(?s).*(not working|issue|problem|broken|unable|failed|disconnect).*"))
            return "CampusDesk opens only when the Windows computer running it is connected to the configured VNRVJIET Wi-Fi. Wi-Fi alone does not verify a student's identity.";
        if(q.contains("attachment")||q.contains("upload a file"))
            return "Open a request under 'My requests' and attach a PDF, PNG, JPG, or TXT file (up to 450 KB). Do not upload passwords or confidential records to this prototype.";
        return "";
    }
    static String missingRecordsHelp(String q,boolean hasOwnRequests){
        if(q.matches("(?s).*(my tickets?|my requests?|ticket status|request status|track (a |my )?(ticket|request)|cd-[0-9]+).*"))
            return hasOwnRequests?"I can't find a matching ticket number in this browser workspace. Open 'My requests' to view your own tickets.":"No requests have been created in this browser workspace yet. You can describe a problem and I can prepare a draft.";
        if(q.contains("attend"))return "No attendance records are available for this visitor workspace. CampusDesk is not connected to live EduPrime; check the authorized college portal for your attendance.";
        if(q.matches("(?s).*(marks|grades|results|score).*"))return "I can't access your marks or exam results from EduPrime. Please check the official portal. If a displayed result is wrong, explain the issue and I can prepare a request.";
        if(q.matches("(?s).*(fees?|payment|receipt|transaction).*"))return "There are no authorized payment records for this browser workspace. I cannot verify whether a fee was paid; check the official college portal or share the issue so I can prepare a request.";
        if(q.matches("(?s).*(timetable|class schedule|class period|class timetable).*"))return "CampusDesk does not provide class timetables. Please consult your department’s official schedule.";
        if(q.matches("(?s).*(faculty|lecturer|professor|where is|teacher).*"))return "Use the College Directory for publicly listed faculty names. CampusDesk does not verify anyone’s current location.";
        return "";
    }
    static String articleSnippet(Map<String,Object> article){
        String name=s(article.get("title")),content=s(article.get("content"));
        if(content.length()>750)content=content.substring(0,750)+"…";
        return "From the local knowledge article '"+name+"':\n"+content;
    }
    static String describeRecords(List<Map<String,Object>> records,String question){
        if(records.isEmpty())return "";
        String q=question.toLowerCase(Locale.ROOT);
        List<String> lines=new ArrayList<>();
        for(var row:records){
            if(row.containsKey("title")&&row.containsKey("content")){
                lines.add(articleSnippet(row));
            }else if(row.containsKey("present")&&row.containsKey("total")){
                try{int present=Integer.parseInt(s(row.get("present"))),total=Integer.parseInt(s(row.get("total")));
                    lines.add(s(row.get("subject"))+": "+present+" / "+total+" classes ("+(total>0?Math.round(100.0*present/total)+"%":"percentage unavailable")+")");
                }catch(Exception e){lines.add("Attendance record available for "+s(row.get("subject")));}
            }else if(row.containsKey("paymentStatus")){
                lines.add(s(row.get("label"))+": payment "+s(row.get("paymentStatus"))+", portal "+s(row.get("portalStatus")));
            }else if(row.containsKey("status")&&row.containsKey("id")){
                lines.add("Ticket CD-"+s(row.get("id"))+": "+s(row.get("subject"))+" — "+s(row.get("status")));
            }else if(row.containsKey("name")&&row.containsKey("department")){
                lines.add(s(row.get("name"))+" — "+s(row.get("department"))+(s(row.get("office")).isBlank()?"":"; office: "+s(row.get("office"))));
            }else if(row.containsKey("period")&&row.containsKey("day")){
                lines.add(s(row.get("day"))+" / "+s(row.get("period"))+": "+s(row.get("subject"))+(s(row.get("room")).isBlank()?"":" in "+s(row.get("room"))));
            }
            if(lines.size()>=8)break;
        }
        if(lines.isEmpty())return "I found "+records.size()+" matching authorized local records. Expand the record details below.";
        return "Available CampusDesk records (not a live EduPrime lookup):\n"+String.join("\n",lines);
    }
    Object assistant(Map<String,Object> u,Map<String,Object> b){
        String question=required(b,"message",1200);
        Map<String,Object> route=route(question);
        String mode=s(route.get("path")),lower=question.toLowerCase(Locale.ROOT);
        List<Map<String,Object>> context=new ArrayList<>();
        synchronized(db){
            String uid=s(u.get("id"));
            if(lower.contains("attend"))for(var r:db.rows("attendance"))if(eq(r.get("studentId"),uid))context.add(new LinkedHashMap<>(r));
            if(lower.contains("fee")||lower.contains("payment")||lower.contains("receipt"))for(var r:db.rows("payments"))if(eq(r.get("studentId"),uid))context.add(new LinkedHashMap<>(r));
            if(lower.matches("(?s).*(faculty|lecturer|professor|teacher|department).*")){
                for(var r:facultyDirectory()){
                    String dept=s(r.get("department")).toLowerCase(Locale.ROOT);
                    if(lower.contains("cse")&&!dept.contains("cse")&&!dept.contains("computer science"))continue;
                    if(lower.contains("ece")&&!dept.contains("communication"))continue;
                    if(lower.contains("eee")&&!dept.contains("electrical"))continue;
                    if(lower.contains("civil")&&!dept.contains("civil"))continue;
                    if(lower.contains("mechanical")&&!dept.contains("mechanical"))continue;
                    if(lower.contains("it faculty")&&!dept.contains("information technology"))continue;
                    context.add(new LinkedHashMap<>(r));
                }
            }
            if(lower.contains("ticket")||lower.contains("request")||lower.matches("(?s).*cd-[0-9]+.*")){
                Matcher ticketNumber=Pattern.compile("(?i)CD-([0-9]+)").matcher(question);
                String wanted=ticketNumber.find()?ticketNumber.group(1):"";
                for(var r:db.rows("tickets"))if(eq(r.get("studentId"),uid)&&(wanted.isBlank()||eq(r.get("id"),wanted)))
                    context.add(m("id",r.get("id"),"subject",r.get("subject"),"status",r.get("status"),"department",r.get("department")));
            }
            if(context.isEmpty()){
                Set<String> words=new HashSet<>(Arrays.asList(lower.split("[^a-z0-9]+")));
                words.removeAll(Set.of("what","when","where","can","you","the","and","for","are","how","is","my","do","i","a","in","to","of","please","about"));
                if(words.size()>0)for(var a:db.rows("articles")){
                    String title=(s(a.get("title"))+" "+s(a.get("category"))).toLowerCase(Locale.ROOT);
                    if(words.stream().anyMatch(w->w.length()>2&&title.contains(w)))context.add(m("title",a.get("title"),"category",a.get("category"),"content",a.get("content")));
                }
            }
        }
        if(context.size()>12)context=new ArrayList<>(context.subList(0,12));
        boolean timetableQuestion=lower.matches("(?s).*(timetable|class schedule|class period).* ".trim());
        String response=timetableQuestion?"CampusDesk does not provide class timetables. Please consult your department’s official schedule.":platformHelp(lower);
        if(response.isEmpty()&&!context.isEmpty())response=describeRecords(context,question);
        if(response.isEmpty()){
            boolean hasOwnRequests;
            synchronized(db){hasOwnRequests=db.rows("tickets").stream().anyMatch(t->eq(t.get("studentId"),u.get("id")));}
            response=missingRecordsHelp(lower,hasOwnRequests);
        }
        if(response.isEmpty()){
            if(mode.equals("ask_clarification"))response="Tell me a little more about the issue so I can suggest the right step. For example, 'My exam fee isn't reflected' or 'How can I find a faculty member?'";
            else if(mode.equals("send_to_person")||mode.equals("agent_investigation"))response="I can't change college records myself. I can prepare a request to "+route.get("department")+" based on what you've described. Review it and submit only if it's correct.";
            else response="I couldn't find matching authorized records in this CampusDesk installation. No live EduPrime data was checked. You can describe the problem and I can help prepare a request.";
        }
        Map<String,Object> draft=null;
        if((mode.equals("send_to_person")||mode.equals("agent_investigation"))&&DEPTS.contains(s(route.get("department")))){
            String subject=question.replaceAll("\\s+"," ").trim();
            if(subject.length()>105)subject=subject.substring(0,102)+"…";
            draft=m("subject",subject,"description",question,"department",route.get("department"),"priority","medium");
        }
        boolean modelUsed=false;
        String aiProvider="Local help and read-only lookup; no external AI model configured";
        if(!timetableQuestion && AgyCli.enabled()){
            AgyCli.Reply ai=AgyCli.answer(question,response,context);
            response=ai.answer();
            modelUsed=ai.success();
            aiProvider=ai.source();
        }else if(!timetableQuestion && agyBridge.enabled){
            AgyBridge.Reply ai=agyBridge.answer(question,response,context);
            response=ai.answer();
            modelUsed=ai.success();
            aiProvider=ai.source();
        }else if(!timetableQuestion && !AgyCli.enabled() && !Smtp.env("AGENT_API_KEY").isBlank()&&!Smtp.env("AGENT_API_URL").isBlank()){
            // A model may answer general queries without record export. If local records matched,
            // retain the on-device answer unless an administrator explicitly opted into export.
            boolean shareRecords=Smtp.env("AGENT_SEND_RECORDS").equals("1");
            if(context.isEmpty()||shareRecords)try{
                var msgs=List.of(m("role","system","content","You are CampusDesk's college helpdesk assistant. Give concise helpful guidance from authorized local records or general process advice. Do not invent actual college policies, attendance, marks, locations, or live EduPrime access. If a case needs staff action, advise the student to review and submit a ticket. Never claim you changed records or contacted a department."),
                    m("role","user","content","Question: "+question+"\n"+(shareRecords?"Authorized local records (opt-in): "+Json.stringify(context)+"\nCurrent local guidance: "+response:"No local records are included; explain only general process information and avoid unverified facts.")));
                var payload=m("model",Smtp.env("AGENT_MODEL").isBlank()?"gpt-4.1-mini":Smtp.env("AGENT_MODEL"),"messages",msgs,"temperature",0.1);
                var req=HttpRequest.newBuilder(URI.create(Smtp.env("AGENT_API_URL"))).timeout(Duration.ofSeconds(12)).header("Authorization","Bearer "+Smtp.env("AGENT_API_KEY")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload))).build();
                var res=external.send(req,HttpResponse.BodyHandlers.ofString());
                if(res.statusCode()==200){var cs=(List<?>)Json.object(Json.parse(res.body())).get("choices");if(!cs.isEmpty()){
                    String generated=s(Json.object(Json.object(cs.get(0)).get("message")).get("content"));
                    if(!generated.isBlank()){response=generated;modelUsed=true;}
                }}
            }catch(Exception ignored){}
        }
        if(!timetableQuestion && !AgyCli.enabled() && !agyBridge.enabled && (!Smtp.env("AGENT_API_KEY").isBlank()&&!Smtp.env("AGENT_API_URL").isBlank()))
            aiProvider=modelUsed?"Configured external AI model":"Local guidance (external AI unavailable)";
        synchronized(db){db.audit(s(u.get("id")),"assistant_query",s(route.get("provider"))+"/"+mode+(modelUsed?"/ai":"/local"));persist();}
        return m("answer",response,"route",route,"records",context,"ticketDraft",draft,"agentProvider",aiProvider,"eduprimeLive",false);
    }
    void serveStatic(HttpExchange h,String raw)throws IOException {String path=raw.equals("/")?"/index.html":raw;Path root=frontend.toAbsolutePath().normalize();Path p=root.resolve(path.substring(1)).normalize();if(!p.startsWith(root)||!Files.isRegularFile(p)){if(!path.startsWith("/assets/")&&Files.isRegularFile(root.resolve("index.html")))p=root.resolve("index.html");else {sendBytes(h,404,"text/plain",("React build not found. Run 'npm install' and 'npm run build' inside frontend, then restart CampusDesk.").getBytes(StandardCharsets.UTF_8));return;}}String filename=p.toString();String type=filename.endsWith(".html")?"text/html; charset=utf-8":filename.endsWith(".js")?"text/javascript; charset=utf-8":filename.endsWith(".css")?"text/css; charset=utf-8":filename.endsWith(".svg")?"image/svg+xml":"application/octet-stream";if(filename.endsWith(".html"))h.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'");sendBytes(h,200,type,Files.readAllBytes(p));}
    void stream(HttpExchange h,Map<String,Object> u)throws IOException {h.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");h.getResponseHeaders().set("Cache-Control","no-store");h.getResponseHeaders().set("Connection","keep-alive");h.sendResponseHeaders(200,0);try(OutputStream out=h.getResponseBody()){long last=-1,start=ms();while(ms()-start<45_000){if(!wifi.allowed()||sessionUser(h)==null)break;long v=version;if(v!=last){out.write(("event: update\ndata: {\"version\":"+v+"}\n\n").getBytes(StandardCharsets.UTF_8));last=v;}else out.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));out.flush();Thread.sleep(1500);} }catch(Exception ignored){} }
    void wifiWaitingPage(HttpExchange h)throws IOException{
        String html="""
            <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>CampusDesk · College Wi-Fi required</title><style>
            *{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f7f6f3;color:#1e1d1a;font-family:system-ui,-apple-system,Segoe UI,sans-serif;padding:20px}
            .panel{width:min(480px,100%);background:white;border:1px solid #e3e1dc;border-radius:20px;padding:38px;box-shadow:0 12px 45px #2020200b}
            .logo{font-size:22px;font-weight:800;margin-bottom:32px}.logo span{color:#888}.tag{letter-spacing:2px;font-size:11px;color:#777;font-weight:700}
            h1{line-height:1.14;font-size:32px;margin:16px 0}p{line-height:1.7;color:#666}.status{background:#f4f4f1;border-radius:10px;padding:15px;margin:24px 0;font-weight:600}
            small{color:#777}button{width:100%;background:#1b1a18;color:white;padding:14px;border:0;border-radius:10px;font-weight:700;cursor:pointer}
            </style></head><body><div class="panel"><div class="logo">C<span>●</span> campusdesk</div><div class="tag">VNRVJIET · CAMPUS ACCESS</div>
            <h1>Connect to college Wi-Fi</h1><p>No login or OTP is required. The dashboard opens automatically only when this computer is connected to the configured VNRVJIET wireless network.</p>
            <div class="status" id="status">Checking Wi-Fi connection…</div><button onclick="check()">Check connection now ↗</button>
            <p><small>This check uses your Windows Wi-Fi connection. The system has no access to real EduPrime student records.</small></p></div>
            <script>async function check(){try{let r=await fetch('/api/network/status',{cache:'no-store'}),x=await r.json();
            document.getElementById('status').textContent=x.allowed?'College Wi-Fi connected. Opening dashboard…':
            'Current Wi-Fi: '+(x.currentSsid||'Not connected')+' · Expected: '+x.requirement;
            if(x.allowed)location.replace('/');}catch{document.getElementById('status').textContent='Could not check connection.'}}
            check();setInterval(check,3000);</script></body></html>""";
        sendBytes(h,200,"text/html; charset=utf-8",html.getBytes(StandardCharsets.UTF_8));
    }
    void handler(HttpExchange h){try{
        String path=h.getRequestURI().getPath();String method=h.getRequestMethod();
        if(method.equals("GET")&&path.equals("/api/network/status")){
            respond(h,200,m("allowed",wifi.allowed(),"currentSsid",wifi.currentSsid(),"requirement",wifi.requirement(),"networkOnly",true));return;
        }
        if(!wifi.allowed()){
            if(path.startsWith("/api/"))respond(h,403,m("error","College Wi-Fi required","wifiRequired",true));
            else if(method.equals("GET"))wifiWaitingPage(h);
            else respond(h,403,m("error","College Wi-Fi required"));
            return;
        }

        if(method.equals("OPTIONS")){respond(h,405,m("error","Cross-origin requests are disabled"));return;}
        // Reject forged cross-site writes; the browser also sets SameSite=Lax session cookies.
        if(!Set.of("GET","HEAD").contains(method)){
            String origin=h.getRequestHeaders().getFirst("Origin");String host=h.getRequestHeaders().getFirst("Host");
            if(origin!=null&&host!=null){URI o=URI.create(origin);check((o.getHost().equals("localhost")||o.getHost().equals("127.0.0.1"))&&host.equalsIgnoreCase(o.getAuthority()),403,"Cross-origin request rejected");}
            String ct=h.getRequestHeaders().getFirst("Content-Type");check(ct!=null&&ct.startsWith("application/json"),415,"Only application/json supported");
        }
        if(method.equals("GET")&&path.equals("/api/health")){respond(h,200,m("status","ok","server","java21","records",db.count("users"),"seeded",false));return;}
        if(method.equals("GET")&&path.equals("/api/config")){respond(h,200,config());return;}
        if(method.equals("GET")&&path.equals("/api/ai/status")){respond(h,200,m("chatEngine",AgyCli.enabled()?"AGY CLI":agyBridge.enabled?"AGY HTTP bridge":"Offline local guidance", "agyCliEnabled",AgyCli.enabled(),"agyBridgeEnabled",agyBridge.enabled,"jevEnabled",!Smtp.env("JEV_API_KEY").isBlank(),"note","Configured is not proof of live account access; check answer source after asking."));return;}
        if(path.startsWith("/api/")){
            Map<String,Object> u=require(h);
            if(method.equals("GET")&&path.equals("/api/bootstrap")){respond(h,200,bootstrap(u));return;}
            if(method.equals("GET")&&path.equals("/api/events")){stream(h,u);return;}
            if(method.equals("POST")&&path.equals("/api/tickets")){rate("new-ticket:"+u.get("id"),12,3600_000L);respond(h,201,newTicket(u,body(h)));return;}


            if(method.equals("POST")&&path.equals("/api/admin/claims/review")){respond(h,200,reviewClaim(u,body(h)));return;}
            if(method.equals("POST")&&path.equals("/api/admin/staff")){respond(h,201,createStaff(u,body(h)));return;}
            if(method.equals("POST")&&path.equals("/api/admin/articles")){respond(h,201,addArticle(u,body(h)));return;}
            if(method.equals("POST")&&path.equals("/api/admin/import")){respond(h,200,importRows(u,body(h)));return;}
            if(method.equals("GET")&&path.equals("/api/admin/analytics")){respond(h,200,analytics(u));return;}
            if(method.equals("POST")&&path.equals("/api/notifications/read")){var b=body(h);String id=required(b,"id",40);synchronized(db){var n=db.find("notifications","id",id);check(n!=null&&eq(n.get("userId"),u.get("id")),404,"Notification not found");n.put("read",true);persist();}respond(h,200,m("ok",true));return;}
            if(method.equals("POST")&&path.equals("/api/assistant")){rate("assistant:"+u.get("id"),30,600_000L);respond(h,200,assistant(u,body(h)));return;}
            String[] seg=path.split("/");
            if(seg.length>=4&&seg[1].equals("api")&&seg[2].equals("tickets")){
                String id=seg[3];
                if(seg.length==4&&method.equals("GET")){synchronized(db){respond(h,200,fullTicket(u,ticketOrFail(u,id)));}return;}
                if(seg.length==5&&method.equals("POST")){
                    Object out=switch(seg[4]){case "manage"->manage(u,id,body(h));case "approval"->requestApproval(u,id);case "attachment"->upload(u,id,body(h));default->throw new ApiException(404,"Endpoint not found");};respond(h,200,out);return;
                }
                if(seg.length==6&&seg[4].equals("attachment")&&method.equals("GET")){synchronized(db){ticketOrFail(u,id);var att=db.find("attachments","id",seg[5]);check(att!=null&&eq(att.get("ticketId"),id),404,"Attachment not found");h.getResponseHeaders().set("Content-Disposition","attachment; filename=\"download\"");h.getResponseHeaders().set("X-Content-Type-Options","nosniff");sendBytes(h,200,s(att.get("type")),Base64.getDecoder().decode(s(att.get("data"))));}return;}
            }
            if(path.startsWith("/api/approvals/")&&method.equals("POST")){respond(h,200,approve(u,path.substring("/api/approvals/".length()),body(h)));return;}
            throw new ApiException(404,"Endpoint not found");
        }
        if(method.equals("GET"))serveStatic(h,path);else throw new ApiException(405,"Method not allowed");
    }catch(ApiException e){try{respond(h,e.status,m("error",e.getMessage()));}catch(IOException ignored){}}catch(Exception e){System.err.println("Request error: "+e.getClass().getSimpleName()+": "+e.getMessage());try{respond(h,500,m("error","Internal server error"));}catch(IOException ignored){}}finally{h.close();}}
    void run(int port)throws Exception{HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);server.createContext("/",this::handler);server.setExecutor(Executors.newCachedThreadPool());server.start();emailWorker.scheduleWithFixedDelay(()->{try{sendOutbox();}catch(Exception ex){System.err.println("Outbox error: "+ex.getClass().getSimpleName());}},5,30,TimeUnit.SECONDS);System.out.println("CampusDesk Java backend running: http://127.0.0.1:"+port);System.out.println("No login or SMTP required. College Wi-Fi: "+wifi.requirement()+" (current: "+(wifi.currentSsid().isBlank()?"not detected":wifi.currentSsid())+")");}
    public static void main(String[] args)throws Exception{
        Path data=Path.of(System.getenv().getOrDefault("CAMPUSDESK_DATA","data/campusdesk.json"));
        int port=args.length>0?Integer.parseInt(args[0]):8000;
        new CampusDesk(data).run(port);
    }
}
