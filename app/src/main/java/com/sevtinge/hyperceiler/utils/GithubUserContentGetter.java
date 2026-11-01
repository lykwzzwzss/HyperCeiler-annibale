package com.sevtinge.hyperceiler.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.sevtinge.hyperceiler.common.log.AndroidLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class GithubUserContentGetter {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_CONTRIBUTOR_QUERY_THREADS = 4;

    public static ArrayList<String> contributors = new ArrayList<>();

    public static Bitmap getUserAvatar(String userId, String s) {
        String url = "https://avatars.githubusercontent.com/u/" + userId + "?s=" + s;
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url);
            int code = conn.getResponseCode();
            if (code != 200) {
                AndroidLog.w("GithubUserContentGetter", "Get user avatar failed with HTTP CODE " + code + ".");
                return null;
            }
            try (InputStream input = conn.getInputStream()) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (Exception e) {
            AndroidLog.w("GithubUserContentGetter", "Failed when get user avatar: " + e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static String getUserName(String user) {
        HttpURLConnection conn = null;
        try {
            String apiUrl = "https://api.github.com/users/" + user;
            conn = openConnection(apiUrl);

            int code = conn.getResponseCode();
            if (code != 200) {
                AndroidLog.w("GithubUserContentGetter", "Get user name failed with HTTP CODE " + code + ".");
                return null;
            }

            JSONObject json = new JSONObject(readResponse(conn));

            String name = json.optString("name", null);
            String login = json.optString("login", user);

            if (name != null && !name.isEmpty()) {
                return name;
            } else {
                return login;
            }

        } catch (Exception e) {
            AndroidLog.w("GithubUserContentGetter", "Failed when get user name: " + e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static ArrayList<String> getContributors(String owner, String repo) {
        if (!contributors.isEmpty()) return contributors;

        String response;
        HttpURLConnection conn = null;
        try {
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/contributors";
            conn = openConnection(apiUrl);

            int code = conn.getResponseCode();
            if (code != 200) {
                AndroidLog.w("GithubUserContentGetter", "Get contributors failed with HTTP CODE " + code + ".");
                return contributors;
            }
            response = readResponse(conn);
        } catch (Exception e) {
            AndroidLog.w("GithubUserContentGetter", "Failed when get contributors: " + e);
            return contributors;
        } finally {
            if (conn != null) conn.disconnect();
        }

        // GitHub 没有批量用户信息接口，这里复用连接池并把并发限制为 4，避免 N+1 请求瞬间打满网络
        ArrayList<String> result = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(MAX_CONTRIBUTOR_QUERY_THREADS);
        try {
            JSONArray array = new JSONArray(response);
            List<Future<String>> futures = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String login = obj.optString("login", null);
                if (login != null && !login.isEmpty()) {
                    futures.add(executor.submit(() -> {
                        String name = getUserName(login);
                        if (name == null || name.isEmpty() || name.equals("null")) {
                            name = login;
                        }
                        return "@" + login + "," + name;
                    }));
                }
            }

            for (Future<String> future : futures) {
                try {
                    String entry = future.get();
                    if (entry != null) {
                        result.add(entry);
                    }
                } catch (Exception e) {
                    AndroidLog.w("GithubUserContentGetter", "Failed when resolve contributor name: " + e);
                }
            }
        } catch (Exception e) {
            AndroidLog.w("GithubUserContentGetter", "Failed when parse contributors: " + e);
        } finally {
            executor.shutdownNow();
        }

        contributors.addAll(result);
        return contributors;
    }

    private static HttpURLConnection openConnection(String apiUrl) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Android");
        return conn;
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
