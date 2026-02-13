package org.eclipse.agents.contexts.util;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class JsonUtil {

  private JsonUtil() {}

  public static String toJson(Object value) {
    StringBuilder sb = new StringBuilder(4096);
    write(value, sb);
    return sb.toString();
  }

  // ---------------- core writer ----------------

  @SuppressWarnings("unchecked")
  private static void write(Object value, StringBuilder sb) {

    if (value == null) {
      sb.append("null");
      return;
    }

    if (value instanceof String s) {
      writeString(s, sb);
      return;
    }

    if (value instanceof Number || value instanceof Boolean) {
      sb.append(value.toString());
      return;
    }

    if (value instanceof Map<?, ?> map) {
      writeMap((Map<String, Object>) map, sb);
      return;
    }

    if (value instanceof List<?> list) {
      writeList(list, sb);
      return;
    }

    // Fallback: serialize unknown types via toString() as JSON string
    writeString(value.toString(), sb);
  }

  private static void writeMap(Map<String, Object> map, StringBuilder sb) {
    sb.append('{');

    Iterator<Map.Entry<String, Object>> it = map.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, Object> e = it.next();

      writeString(e.getKey(), sb);
      sb.append(':');
      write(e.getValue(), sb);

      if (it.hasNext()) {
        sb.append(',');
      }
    }

    sb.append('}');
  }

  private static void writeList(List<?> list, StringBuilder sb) {
    sb.append('[');

    for (int i = 0; i < list.size(); i++) {
      write(list.get(i), sb);
      if (i < list.size() - 1) {
        sb.append(',');
      }
    }

    sb.append(']');
  }

  // ---------------- string escaping ----------------

  private static void writeString(String s, StringBuilder sb) {
    sb.append('"');

    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);

      switch (c) {
        case '"':  sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;

        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }

    sb.append('"');
  }

  // ---------------- optional pretty print ----------------

  public static String toPrettyJson(Object value) {
    String raw = toJson(value);
    return prettyFormat(raw);
  }

  private static String prettyFormat(String json) {
    StringBuilder out = new StringBuilder();
    int indent = 0;
    boolean inString = false;

    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);

      if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
        inString = !inString;
      }

      if (!inString) {
        switch (c) {
          case '{':
          case '[':
            out.append(c).append('\n');
            indent++;
            appendIndent(out, indent);
            continue;

          case '}':
          case ']':
            out.append('\n');
            indent--;
            appendIndent(out, indent);
            out.append(c);
            continue;

          case ',':
            out.append(c).append('\n');
            appendIndent(out, indent);
            continue;

          case ':':
            out.append(": ");
            continue;
        }
      }

      out.append(c);
    }

    return out.toString();
  }

  private static void appendIndent(StringBuilder sb, int indent) {
    for (int i = 0; i < indent; i++) {
      sb.append("  ");
    }
  }
}