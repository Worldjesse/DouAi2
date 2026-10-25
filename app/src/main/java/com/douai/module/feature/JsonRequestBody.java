package com.douai.module.feature;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Writes model JSON without retaining a second complete JSON string and UTF-8 byte array. */
final class JsonRequestBody {
    interface Check { void run() throws Exception; }
    private JsonRequestBody() { }

    static long byteLength(JSONObject body, Check check) throws Exception {
        CountingOutput output = new CountingOutput();
        write(body, output, check);
        return output.count;
    }

    static void write(JSONObject body, OutputStream output, Check check) throws Exception {
        Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        value(body, writer, check);
        writer.flush();
    }

    private static void value(Object value, Writer out, Check check) throws Exception {
        check.run();
        if (value == null || value == JSONObject.NULL) { out.write("null"); return; }
        if (value instanceof String) { string((String) value, out, check); return; }
        if (value instanceof Number) { out.write(JSONObject.numberToString((Number) value)); return; }
        if (value instanceof Boolean) { out.write(value.toString()); return; }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            out.write('{'); boolean comma = false;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (comma) out.write(','); comma = true;
                string(key, out, check); out.write(':'); value(object.get(key), out, check);
            }
            out.write('}'); return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; out.write('[');
            for (int i = 0; i < array.length(); i++) {
                if (i != 0) out.write(','); value(array.get(i), out, check);
            }
            out.write(']'); return;
        }
        throw new IllegalArgumentException("Unsupported model JSON value");
    }

    private static void string(String value, Writer out, Check check) throws Exception {
        out.write('"'); int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if ((i & 8191) == 0) check.run();
            char c = value.charAt(i);
            String escape = c == '"' ? "\\\"" : c == '\\' ? "\\\\"
                    : c == '\n' ? "\\n" : c == '\r' ? "\\r" : c == '\t' ? "\\t"
                    : c == '\b' ? "\\b" : c == '\f' ? "\\f" : null;
            if (escape != null || c < 32 || c == '\u2028' || c == '\u2029') {
                out.write(value, start, i - start);
                if (escape != null) out.write(escape);
                else {
                    out.write("\\u");
                    for (int shift = 12; shift >= 0; shift -= 4) out.write("0123456789abcdef".charAt((c >>> shift) & 15));
                }
                start = i + 1;
            } else if (i - start >= 8191) {
                // Do not split a surrogate pair at the writer's chunk boundary.
                if (Character.isHighSurrogate(c)) continue;
                out.write(value, start, i - start + 1); start = i + 1;
            }
        }
        out.write(value, start, value.length() - start); out.write('"');
    }

    private static final class CountingOutput extends OutputStream {
        long count;
        @Override public void write(int value) { count++; }
        @Override public void write(byte[] bytes, int offset, int length) { count += length; }
    }
}
