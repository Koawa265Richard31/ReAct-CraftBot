package com.dwinovo.numen.platform;

/**
 * 单测的空壳配置:一切都空,写进去的只落在内存里。只为让 {@code Services} 装得上。
 */
public final class TestNumenConfig implements com.dwinovo.numen.platform.services.INumenConfig {

    private final java.util.Map<String, String> values = new java.util.HashMap<>();

    private String get(String key) {
        return values.getOrDefault(key, "");
    }

    private void set(String key, String value) {
        values.put(key, value == null ? "" : value);
    }

    @Override public String getApiKey() { return get("apiKey"); }
    @Override public String getBaseUrl() { return get("baseUrl"); }
    @Override public String getModel() { return get("model"); }
    @Override public String getProvider() { return get("provider"); }
    @Override public String getProxy() { return get("proxy"); }
    @Override public String getReasoningEffort() { return get("reasoningEffort"); }
    @Override public String getSystemPrompt() { return get("systemPrompt"); }
    @Override public void setApiKey(String value) { set("apiKey", value); }
    @Override public void setBaseUrl(String value) { set("baseUrl", value); }
    @Override public void setModel(String value) { set("model", value); }
    @Override public void setProvider(String value) { set("provider", value); }
    @Override public void setProxy(String value) { set("proxy", value); }
    @Override public void setSystemPrompt(String value) { set("systemPrompt", value); }
    @Override public void setReasoningEffort(String value) { set("reasoningEffort", value); }
    @Override public String getSttProvider() { return get("sttProvider"); }
    @Override public String getSttApiKey() { return get("sttApiKey"); }
    @Override public String getSttBaseUrl() { return get("sttBaseUrl"); }
    @Override public String getSttModel() { return get("sttModel"); }
    @Override public String getSttMicrophone() { return get("sttMicrophone"); }
    @Override public void setSttProvider(String value) { set("sttProvider", value); }
    @Override public void setSttApiKey(String value) { set("sttApiKey", value); }
    @Override public void setSttBaseUrl(String value) { set("sttBaseUrl", value); }
    @Override public void setSttModel(String value) { set("sttModel", value); }
    @Override public void setSttMicrophone(String value) { set("sttMicrophone", value); }
    @Override public void save() {}
}
