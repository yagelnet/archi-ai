package local.archi.ai;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** An MCP server connection: Archi or an additional server such as Jira/Confluence. */
interface McpTools {
    List<McpClient.Tool> listTools() throws IOException;

    McpClient.CallResult callTool(String name, Map<String, Object> args) throws IOException;

    /** Releases the connection. */
    default void close() {}
}
