import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Private persisted state; no synthetic records or automatic seeds. */
public final class Store {
    public final Path path;
    private final Map<String,Object> root;
    private static final List<String> TABLES=List.of("users","claims","tickets","events","notifications","articles","attendance","payments","faculty","timetable","subjects","audit","attachments","approvals","emailOutbox","guestSessions");
    public Store(Path path)throws Exception{
        this.path=path;
        if(Files.exists(path))root=Json.object(Json.parse(Files.readString(path,StandardCharsets.UTF_8)));
        else root=new LinkedHashMap<>();
        for(String key:TABLES)root.putIfAbsent(key,new ArrayList<>());
        root.putIfAbsent("sequence",0L);
    }
    public synchronized List<Map<String,Object>> rows(String table){if(!TABLES.contains(table))throw new IllegalArgumentException("Unknown table");return Json.objects(root.get(table));}
    public synchronized Map<String,Object> find(String table,String field,String value){for(var r:rows(table))if(Json.s(r.get(field)).equals(value))return r;return null;}
    public synchronized String id(){long n=((Number)root.get("sequence")).longValue()+1;root.put("sequence",n);return String.valueOf(n);}
    public synchronized void save()throws Exception{
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path p=path.resolveSibling(path.getFileName()+".tmp");
        Files.writeString(p,Json.stringify(root),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        try{Files.move(p,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(p,path,StandardCopyOption.REPLACE_EXISTING);}
    }
    public synchronized void audit(String actor,String action,String detail){rows("audit").add(new LinkedHashMap<>(Map.of("id",id(),"actor",actor,"action",action,"detail",detail,"at",java.time.Instant.now().toString())));}
    public synchronized int count(String table){return rows(table).size();}
}
