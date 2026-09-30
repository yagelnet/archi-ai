package local.archi.ai;

import java.io.File;
import java.util.concurrent.CountDownLatch;

public class SmokeTest {
    public static void main(String[] a) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        ClaudeProcess p = new ClaudeProcess(new ClaudeProcess.Listener() {
            public void onText(String t) { System.out.print(t); }
            public void onTool(String n, String d) { System.out.println("\n[TOOL] " + n + " " + d); }
            public void onToolError(String t) { System.out.println("\n[TOOL-ERR] " + t); }
            public void onSystem(String t) { System.out.println("[SYS] " + t); }
            public void onTurnDone(boolean e, String i) { System.out.println("\n[DONE] err=" + e + " " + i); done.countDown(); }
            public void onExit(int c, String s) { System.out.println("[EXIT] " + c + " " + s); done.countDown(); }
        });
        p.start(new File(System.getProperty("user.home")));
        p.send("Вызови get-model-info и ответь одной строкой: имя модели и число элементов.", java.util.List.of());
        done.await(java.util.concurrent.TimeUnit.SECONDS.toSeconds(180), java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("[SESSION] " + p.sessionId());
        p.stop();
    }
}
