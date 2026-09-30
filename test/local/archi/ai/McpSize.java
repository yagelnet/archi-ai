package local.archi.ai;
import java.util.*;
public class McpSize {
  static void strip(Object o){ if(o instanceof Map<?,?> m){ Object d=m.get("description"); if(d instanceof String s && s.length()>200) ((Map<String,Object>)m).put("description", s.substring(0,200)); for(Object v: m.values()) strip(v);} else if(o instanceof List<?> l){ for(Object v:l) strip(v);} }
  public static void main(String[] a) throws Exception {
    var tools = new McpClient("http://127.0.0.1:18090/mcp").listTools();
    int desc=0, schema=0, schemaStripped=0;
    for (var t : tools) { desc += Math.min(800, t.description().length()); schema += Json.write(t.inputSchema()).length(); strip(t.inputSchema()); schemaStripped += Json.write(t.inputSchema()).length(); }
    System.out.println("desc(cut800)=" + desc + " schema=" + schema + " schemaParamDesc200=" + schemaStripped);
  }
}
