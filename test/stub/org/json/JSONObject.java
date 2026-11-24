package org.json;
public class JSONObject {
    public JSONObject() { }
    public JSONObject(String raw) { }
    public JSONObject put(String k, int v) { return this; }
    public JSONObject put(String k, Object v) { return this; }
    public int optInt(String k, int d) { return d; }
    public JSONArray optJSONArray(String k) { return null; }
    public String toString() { return "{}"; }
}
