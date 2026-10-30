import java.util.*;

/** Small, dependency-free JSON codec. Rejects malformed and oversized input at HTTP boundary. */
public final class Json {
    private Json() {}
    public static String stringify(Object value) {
        if(value == null) return "null";
        if(value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        if(value instanceof CharSequence s) {
            StringBuilder b=new StringBuilder("\"");
            for(char c:s.toString().toCharArray()){
                switch(c){case '\"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}
            }
            return b.append('"').toString();
        }
        if(value instanceof Map<?,?> m){StringJoiner s=new StringJoiner(",","{","}");for(var e:m.entrySet())s.add(stringify(String.valueOf(e.getKey()))+":"+stringify(e.getValue()));return s.toString();}
        if(value instanceof Iterable<?> a){StringJoiner s=new StringJoiner(",","[","]");for(Object e:a)s.add(stringify(e));return s.toString();}
        throw new IllegalArgumentException("Not JSON: "+value.getClass());
    }
    public static Object parse(String raw) {Parser p=new Parser(raw); Object x=p.value();p.ws();if(p.i!=raw.length())throw new IllegalArgumentException("Invalid trailing JSON");return x;}
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object o){if(!(o instanceof Map<?,?>)) throw new IllegalArgumentException("Expected JSON object");return (Map<String,Object>)o;}
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> objects(Object o){if(!(o instanceof List<?> a))throw new IllegalArgumentException("Expected JSON array");for(Object x:a)if(!(x instanceof Map))throw new IllegalArgumentException("Expected objects in array");return (List<Map<String,Object>>)o;}
    public static String str(Map<String,Object> m,String name){return String.valueOf(m.getOrDefault(name, ""));}
    public static String s(Object x){return x==null?"":String.valueOf(x);}
    private static final class Parser {
        final String s;int i=0;int depth=0;
        Parser(String s){this.s=s;}
        void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
        boolean take(char c){ws();if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
        char ch(){if(i>=s.length())throw new IllegalArgumentException("Incomplete JSON");return s.charAt(i);}
        Object value(){ws();if(++depth>48)throw new IllegalArgumentException("JSON too deeply nested");try{
            char c=ch();
            if(c=='"')return string();
            if(c=='{'){i++;Map<String,Object> map=new LinkedHashMap<>();if(take('}'))return map;do{ws();if(ch()!='"')throw new IllegalArgumentException("Expected string key");String k=string();if(!take(':'))throw new IllegalArgumentException("Expected colon");if(map.containsKey(k))throw new IllegalArgumentException("Duplicate key");map.put(k,value());if(take('}'))return map;}while(take(','));throw new IllegalArgumentException("Invalid object");}
            if(c=='['){i++;List<Object> a=new ArrayList<>();if(take(']'))return a;do{a.add(value());if(take(']'))return a;}while(take(','));throw new IllegalArgumentException("Invalid array");}
            if(s.startsWith("true",i)){i+=4;return true;}if(s.startsWith("false",i)){i+=5;return false;}if(s.startsWith("null",i)){i+=4;return null;}
            int st=i;if(c=='-')i++;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;if(i<s.length()&&s.charAt(i)=='.'){i++;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;}if(i<s.length()&&(s.charAt(i)=='e'||s.charAt(i)=='E')){i++;if(i<s.length()&&(s.charAt(i)=='+'||s.charAt(i)=='-'))i++;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;}
            if(st==i)throw new IllegalArgumentException("Invalid JSON value");String number=s.substring(st,i);try{if(number.contains(".")||number.contains("e")||number.contains("E"))return Double.parseDouble(number);return Long.parseLong(number);}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid number");}
        }finally{depth--;}}
        String string(){if(!take('"'))throw new IllegalArgumentException("Expected quote");StringBuilder b=new StringBuilder();while(i<s.length()){char c=s.charAt(i++);if(c=='"')return b.toString();if(c<' ')throw new IllegalArgumentException("Control character in JSON");if(c=='\\'){if(i>=s.length())throw new IllegalArgumentException("Invalid escape");c=s.charAt(i++);switch(c){case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'b'->b.append('\b');case 'f'->b.append('\f');case '\\'->b.append('\\');case '/'->b.append('/');case '"'->b.append('"');case 'u'->{if(i+4>s.length())throw new IllegalArgumentException("Invalid unicode");try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid unicode");}i+=4;}default->throw new IllegalArgumentException("Invalid escape");}}else b.append(c);}throw new IllegalArgumentException("Unclosed string");}
    }
}
