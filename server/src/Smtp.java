import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Supports authenticated SMTPS (465) or STARTTLS (587); never logs OTPs. */
public final class Smtp {
    private Smtp(){}
    public static boolean configured(){return !env("SMTP_HOST").isBlank()&&!env("SMTP_FROM").isBlank();}
    public static String env(String key){String val=System.getenv(key);return val==null?"":val.trim();}
    static String response(BufferedReader in)throws Exception {String line=in.readLine();if(line==null)throw new IOException("SMTP closed connection");String code=line.length()>=3?line.substring(0,3):"";while(line.length()>3&&line.charAt(3)=='-'){line=in.readLine();if(line==null)throw new IOException("SMTP ended early");}return code;}
    static void expect(BufferedReader in,String...codes)throws Exception{String r=response(in);for(String c:codes)if(r.equals(c))return;throw new IOException("SMTP rejected request: "+r);}
    static void write(BufferedWriter out,String cmd)throws Exception{out.write(cmd+"\r\n");out.flush();}
    public static void send(String to,String title,String text)throws Exception{
        if(!configured())throw new IllegalStateException("Email delivery is not configured. Set SMTP_HOST and SMTP_FROM first.");
        String host=env("SMTP_HOST");String security=env("SMTP_SECURITY").isEmpty()?"starttls":env("SMTP_SECURITY");int port=Integer.parseInt(env("SMTP_PORT").isEmpty()?(security.equalsIgnoreCase("ssl")?"465":"587"):env("SMTP_PORT"));
        Socket socket=security.equalsIgnoreCase("ssl")?SSLSocketFactory.getDefault().createSocket(host,port):new Socket();
        if(!socket.isConnected())socket.connect(new InetSocketAddress(host,port),9000);
        socket.setSoTimeout(12000);
        try(socket){
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            BufferedWriter out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII));
            expect(in,"220");write(out,"EHLO campusdesk.local");expect(in,"250");
            if(!security.equalsIgnoreCase("ssl")){
                if(!security.equalsIgnoreCase("starttls"))throw new IllegalArgumentException("SMTP_SECURITY must be ssl or starttls");
                write(out,"STARTTLS");expect(in,"220");
                SSLSocket secured=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(socket,host,port,true);secured.startHandshake();
                in=new BufferedReader(new InputStreamReader(secured.getInputStream(),StandardCharsets.US_ASCII));out=new BufferedWriter(new OutputStreamWriter(secured.getOutputStream(),StandardCharsets.US_ASCII));
                write(out,"EHLO campusdesk.local");expect(in,"250");
            }
            String user=env("SMTP_USER"),pass=env("SMTP_PASSWORD");
            if(!user.isBlank()){
                if(pass.isBlank())throw new IllegalStateException("SMTP_PASSWORD missing");
                write(out,"AUTH LOGIN");expect(in,"334");write(out,Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)));expect(in,"334");write(out,Base64.getEncoder().encodeToString(pass.getBytes(StandardCharsets.UTF_8)));expect(in,"235");
            }
            String from=env("SMTP_FROM");write(out,"MAIL FROM:<"+from+">");expect(in,"250");write(out,"RCPT TO:<"+to+">");expect(in,"250","251");write(out,"DATA");expect(in,"354");
            String message="From: "+from+"\r\nTo: "+to+"\r\nSubject: "+title.replaceAll("[\r\n]", " ")+"\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"+Base64.getMimeEncoder(76,new byte[]{'\r','\n'}).encodeToString(text.getBytes(StandardCharsets.UTF_8));
            for(String line:message.split("\r?\n",-1))write(out,line.startsWith(".")?"."+line:line);
            write(out,".");expect(in,"250");write(out,"QUIT");
        }
    }
}
