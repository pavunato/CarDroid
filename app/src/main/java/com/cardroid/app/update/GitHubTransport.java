package com.cardroid.app.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class GitHubTransport implements UpdateChecker.Transport {
    public static final String ENDPOINT = "https://api.github.com/repos/pavunato/CarDroid/releases/latest";
    private final URL endpoint;
    public GitHubTransport() {
        try { endpoint = new URL(ENDPOINT); }
        catch (java.net.MalformedURLException impossible) { throw new AssertionError(impossible); }
    }
    // Package private for local HTTP integration tests; app always uses fixed HTTPS endpoint.
    GitHubTransport(URL endpoint) { this.endpoint = endpoint; }
    @Override public UpdateChecker.Response fetch() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "CarDroid-update-checker");
            int code = connection.getResponseCode();
            if (code != 200) return new UpdateChecker.Response(code, "");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 1024 * 1024) throw new IOException("Release response too large");
                    output.write(buffer, 0, count);
                }
                return new UpdateChecker.Response(code, new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
}
