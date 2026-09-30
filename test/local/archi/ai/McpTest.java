package local.archi.ai;
public class McpTest {
  public static void main(String[] a) throws Exception {
    McpClient c = new McpClient("http://127.0.0.1:18090/mcp");
    var tools = c.listTools();
    System.out.println("tools=" + tools.size() + " first=" + tools.get(0).name());
    int chars = 0; for (var t : tools) chars += t.description().length() + Json.write(t.inputSchema()).length();
    System.out.println("total desc+schema chars=" + chars);
    var r = c.callTool("get-model-info", Json.map());
    System.out.println("error=" + r.error() + " text=" + r.text().substring(0, Math.min(160, r.text().length())));
  }
}
