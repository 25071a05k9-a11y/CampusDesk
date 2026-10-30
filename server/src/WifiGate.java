import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.*;

/** Local Windows Wi-Fi SSID gate. NOT equivalent to user identity or robust campus network authentication. */
public final class WifiGate {
    private final Supplier<String> networkName;
    private final String exact;
    private final List<String> prefixes;
    private long checkedAt=0;
    private String cachedSsid="";

    public WifiGate(String exact,String prefixList,Supplier<String> networkName) {
        this.exact=Objects.requireNonNullElse(exact, "").trim();
        this.prefixes=Arrays.stream(Objects.requireNonNullElse(prefixList,"VNRVJIET_E").split(","))
            .map(String::trim).filter(s->!s.isBlank()).toList();
        this.networkName=Objects.requireNonNull(networkName);
        if(this.exact.isBlank()&&this.prefixes.isEmpty())throw new IllegalArgumentException("At least one college Wi-Fi name must be configured");
    }
    public static WifiGate fromEnvironment() {
        return new WifiGate(System.getenv().getOrDefault("CAMPUSDESK_WIFI_SSID",""),
            System.getenv().getOrDefault("CAMPUSDESK_WIFI_SSID_PREFIXES","VNRVJIET_E"),
            WifiGate::readSystemSsid);
    }
    public synchronized String currentSsid() {
        long now=System.currentTimeMillis();
        if(now-checkedAt>2000 || checkedAt==0) {
            try { cachedSsid=Objects.requireNonNullElse(networkName.get(),"").trim(); }
            catch(Exception ignored) { cachedSsid=""; }
            checkedAt=now;
        }
        return cachedSsid;
    }
    public boolean allowed(){return matches(currentSsid());}
    public boolean matches(String ssid){
        if(ssid==null||ssid.isBlank())return false;
        if(!exact.isBlank())return ssid.equals(exact); // Exact match preferred after confirming the actual SSID.
        return prefixes.stream().anyMatch(ssid::startsWith);
    }
    public String requirement(){return exact.isBlank()?String.join(", ",prefixes)+"…":exact;}
    static String readSystemSsid(){
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
        if(os.contains("win")){
            String output=command("netsh","wlan","show","interfaces");
            // BSSID is deliberately excluded. A connected Windows WLAN interface lists an SSID.
            Matcher m=Pattern.compile("(?im)^\\s*SSID\\s*:\\s*(.+?)\\s*$").matcher(output);
            return m.find()?m.group(1).trim():"";
        }
        if(os.contains("linux")){
            String output=command("nmcli","-t","-f","IN-USE,SSID","dev","wifi","list");
            for(String line:output.split("\\R"))if(line.startsWith("*:"))return line.substring(2).replace("\\:",":");
        }
        if(os.contains("mac")){
            for(String iface:List.of("en0","en1")){
                String line=command("networksetup","-getairportnetwork",iface).trim();
                if(line.startsWith("Current Wi-Fi Network:"))return line.substring(line.indexOf(':')+1).trim();
            }
        }
        return ""; // Fail closed on unsupported systems or unreadable WLAN status.
    }
    private static String command(String... args){
        Process p=null;
        try{
            p=new ProcessBuilder(args).redirectErrorStream(true).start();
            if(!p.waitFor(2,TimeUnit.SECONDS)){p.destroyForcibly();return "";}
            try(InputStream in=p.getInputStream()) {return new String(in.readNBytes(12000),StandardCharsets.UTF_8);}
        }catch(Exception ignored){if(p!=null)p.destroyForcibly();return "";}
    }
}
