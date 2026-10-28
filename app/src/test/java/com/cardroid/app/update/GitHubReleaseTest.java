package com.cardroid.app.update;

import org.junit.Test;
import static org.junit.Assert.*;

public class GitHubReleaseTest {
    private String release(String tag, String flags, String assets) {
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"https://github.com/pavunato/CarDroid/releases/tag/" + tag + "\",\"draft\":false,\"prerelease\":" + flags + ",\"assets\":" + assets + "}";
    }
    @Test public void rejectsPageWhoseTagDoesNotMatchVersion() throws Exception {
        String body = release("v0.1.2", "false", "[]").replace("releases/tag/v0.1.2", "releases/tag/v99.0.0");
        assertThrows(IllegalArgumentException.class, () -> GitHubRelease.parse(body));
    }
    @Test public void rejectsUnstableAndMalformedRelease() {
        assertThrows(IllegalArgumentException.class, () -> GitHubRelease.parse(release("v1.0.0", "true", "[]")));
        assertThrows(IllegalArgumentException.class, () -> GitHubRelease.parse(release("v1.0.0-rc.1", "false", "[]")));
        assertThrows(Exception.class, () -> GitHubRelease.parse("{invalid"));
        assertThrows(Exception.class, () -> GitHubRelease.parse("{}"));
    }
    @Test public void noApkFallsBackToReleasePage() throws Exception {
        GitHubRelease r = GitHubRelease.parse(release("v1.0.0", "false", "[]"));
        assertNull(r.downloadUrl);
        assertNotNull(r.pageUrl);
        assertFalse(r.isNewerThan("1.1.0"));
        assertFalse(r.isNewerThan("1.0.0"));
    }
    @Test public void unsafeDownloadIsNotOffered() throws Exception {
        for (String url : new String[]{"http://github.com/pavunato/CarDroid/releases/download/v1.0.0/a.apk", "https://evil.example/a.apk", "https://github.com@evil.example/a.apk"}) {
            assertNull(GitHubRelease.parse(release("v1.0.0", "false", "[{\"name\":\"a.apk\",\"browser_download_url\":\"" + url + "\"}]")).downloadUrl);
        }
    }
    @Test public void ignoresApkFromDifferentTag() throws Exception {
        assertNull(GitHubRelease.parse(release("v1.0.0", "false", "[{\"name\":\"a.apk\",\"browser_download_url\":\"https://github.com/pavunato/CarDroid/releases/download/v9.0.0/a.apk\"}]")).downloadUrl);
    }
    @Test public void readsStableReleaseAndApk() throws Exception {
        GitHubRelease r = GitHubRelease.parse(release("v0.1.2", "false", "[{\"name\":\"CarDroid.apk\",\"browser_download_url\":\"https://github.com/pavunato/CarDroid/releases/download/v0.1.2/CarDroid.apk\"}]"));
        assertEquals("v0.1.2", r.tag);
        assertTrue(r.isNewerThan("0.1.1"));
        assertTrue(r.downloadUrl.endsWith(".apk"));
    }
}
