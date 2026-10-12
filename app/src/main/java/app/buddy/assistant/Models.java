package app.buddy.assistant;

/** Models offered per agent. "" means the agent's own default. */
final class Models {
    static final String[][] CLAUDE = {
            {"", "Default"},
            {"claude-opus-5-5", "Opus 5.5"},
            {"claude-sonnet-5-5", "Sonnet 5.5"},
            {"claude-haiku-5-5", "Haiku 5.5"},
            {"claude-haiku-4-5-20251001", "Haiku 4.5"},
            {"claude-fable-5-1", "Fable 5.1"},
    };
    /** Used until the helper reports Codex's own model list. */
    static final String[][] CODEX = {
            {"", "Default"},
            {"gpt-6.1-sol", "GPT-6.1-Sol"},
            {"gpt-6-astra", "GPT-6-Astra"},
            {"gpt-6-luna", "GPT-6-Luna"},
    };
    /** Used until OpenCode reports its own list (`opencode models`, which includes the free ones). */
    static final String[][] OPENCODE = {
            {"", "Default"},
            {"opencode/big-pickle", "opencode/big-pickle"},
    };
    private static String[][] codexLive, opencodeLive;

    private Models() {
    }

    static String[][] forBackend(String backend) {
        if ("opencode".equals(backend)) return opencodeLive != null ? opencodeLive : OPENCODE;
        if (!"codex".equals(backend)) return CLAUDE;
        return codexLive != null ? codexLive : CODEX;
    }

    /** OpenCode's models as JSON [[id, name], …] ("provider/model" for both). */
    static void setOpencode(String json) {
        String[][] m = parse(json);
        if (m != null) opencodeLive = m;
    }

    /** Codex's model list as reported by the helper: JSON [[slug, name], …]. */
    static void setCodex(String json) {
        String[][] m = parse(json);
        if (m != null) codexLive = m;
    }

    private static String[][] parse(String json) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            if (a.length() == 0) return null;
            String[][] m = new String[a.length() + 1][];
            m[0] = new String[]{"", "Default"};
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONArray e = a.getJSONArray(i);
                m[i + 1] = new String[]{e.getString(0), e.getString(1)};
            }
            return m;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String label(String backend, String model) {
        if (model == null || model.isEmpty()) return "Default";
        for (String[] m : forBackend(backend)) if (m[0].equals(model)) return m[1];
        return model;
    }
}
