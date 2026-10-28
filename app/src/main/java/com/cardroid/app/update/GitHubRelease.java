package com.cardroid.app.update;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;

public final class GitHubRelease {
    public final String tag;
    public final String pageUrl;
    public final String downloadUrl;
    private GitHubRelease(String tag, String pageUrl, String downloadUrl) {
        this.tag = tag; this.pageUrl = pageUrl; this.downloadUrl = downloadUrl;
    }
    public boolean isNewerThan(String installed) { return ReleaseVersion.compare(tag, installed) > 0; }
    public static GitHubRelease parse(String json) throws JSONException {
        JSONObject r = new JSONObject(json);
        String tag = r.getString("tag_name");
        if (r.getBoolean("draft") || r.getBoolean("prerelease") || !ReleaseVersion.isStable(tag))
            throw new IllegalArgumentException("Not a stable published release");
        String page = r.getString("html_url");
        if (!safeUrl(page, "/pavunato/CarDroid/releases/tag/")
                || !page.equals("https://github.com/pavunato/CarDroid/releases/tag/" + tag))
            throw new IllegalArgumentException("Invalid release URL");
        String apk = null;
        JSONArray assets = r.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String url = asset.optString("browser_download_url");
            if (asset.optString("name").endsWith(".apk") && safeUrl(url, "/pavunato/CarDroid/releases/download/" + tag + "/")) {
                // Multiple APKs may target different ABIs; let the user choose on the release page.
                if (apk != null) { apk = null; break; }
                apk = url;
            }
        }
        return new GitHubRelease(tag, page, apk);
    }
    private static boolean safeUrl(String value, String prefix) {
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1
                    && uri.getRawPath().startsWith(prefix) && uri.getRawPath().length() > prefix.length()
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (Exception ignored) { return false; }
    }
}
