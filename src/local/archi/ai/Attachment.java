package local.archi.ai;

import java.io.File;
import java.util.Locale;
import java.util.Map;

/**
 * A file the user attached to a chat message. The plugin never parses the content:
 * it only decides how to hand the file to the model (path for Claude Code, upload for the API).
 */
record Attachment(File file, String name, String mime, Kind kind) {

    enum Kind {
        /** image/png, jpeg, gif, webp — the model sees it directly */
        IMAGE,
        /** application/pdf — the model reads it directly */
        PDF,
        /** plain-text formats — sent to the model as a text document */
        TEXT,
        /** everything else (xlsx, docx, pptx, …) — the model processes it with code execution */
        OTHER
    }

    private static final Map<String, String> MIME = Map.ofEntries(
        Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
        Map.entry("pdf", "application/pdf"),
        Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        Map.entry("xlsm", "application/vnd.ms-excel.sheet.macroEnabled.12"),
        Map.entry("xls", "application/vnd.ms-excel"),
        Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        Map.entry("doc", "application/msword"),
        Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        Map.entry("zip", "application/zip"));

    private static final java.util.Set<String> TEXT_EXT = java.util.Set.of(
        "txt", "csv", "tsv", "md", "json", "xml", "html", "htm", "sql", "yaml", "yml", "log",
        "archimate", "puml", "plantuml", "bpmn", "java", "js", "ts", "py", "properties", "ini", "bsl");

    static Attachment of(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (TEXT_EXT.contains(ext)) return new Attachment(f, n, "text/plain", Kind.TEXT);
        String mime = MIME.getOrDefault(ext, "application/octet-stream");
        Kind kind = mime.startsWith("image/") ? Kind.IMAGE : mime.equals("application/pdf") ? Kind.PDF : Kind.OTHER;
        return new Attachment(f, n, mime, kind);
    }

    long size() {
        return file.length();
    }

    String sizeLabel() {
        long s = size();
        if (s < 1024) return s + " Б";
        if (s < 1024 * 1024) return (s / 1024) + " КБ";
        return String.format("%.1f МБ", s / 1024.0 / 1024.0);
    }
}
