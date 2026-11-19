package com.chess.syncoverlay;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-tier native chess super-engine coordinator.
 * Runs native Java HTTP requests (completely bypassing WebView file:// CORS limits)
 * to 4 real 3300+ ELO cloud engines in parallel, backed by LocalChessEngine (ARM64 native).
 */
public class NativeEngineManager {
    public interface Callback {
        void onBestMove(int token, String uci, String sourceTag);
    }

    private static class CloudCandidate {
        final String uci;
        final String tag;
        final int priority; // higher = better

        CloudCandidate(String uci, String tag, int priority) {
            this.uci = uci;
            this.tag = tag;
            this.priority = priority;
        }
    }

    private final ExecutorService coordinatorPool = Executors.newCachedThreadPool();
    private final ExecutorService localPool = Executors.newSingleThreadExecutor();
    private final LocalChessEngine localEngine = new LocalChessEngine();
    private final AtomicInteger latestToken = new AtomicInteger(0);
    private volatile AtomicBoolean activeLocalStop = new AtomicBoolean(false);

    public void cancelCurrent() {
        latestToken.incrementAndGet();
        activeLocalStop.set(true);
    }

    public void shutdown() {
        cancelCurrent();
        coordinatorPool.shutdownNow();
        localPool.shutdownNow();
    }

    public void requestBestMove(final String fen, final int token, final String legalMovesCsv, final Callback callback) {
        latestToken.set(token);
        activeLocalStop.set(true);
        final AtomicBoolean myStop = new AtomicBoolean(false);
        activeLocalStop = myStop;

        final Set<String> legalSet = new HashSet<>();
        if (legalMovesCsv != null && !legalMovesCsv.isEmpty()) {
            String[] parts = legalMovesCsv.split(",");
            for (String p : parts) {
                String t = p.trim().toLowerCase();
                if (t.length() >= 4) legalSet.add(t);
            }
        }

        // Launch offline ARM64 native engine immediately in parallel as guaranteed strong backup
        final Future<LocalChessEngine.SearchResult> localFuture = localPool.submit(() ->
                localEngine.findBestMove(fen, 2200L, myStop)
        );

        coordinatorPool.execute(() -> {
            final Object lock = new Object();
            final CloudCandidate[] bestCloud = new CloudCandidate[1];
            final AtomicInteger finishedCloudCount = new AtomicInteger(0);
            final int totalClouds = 4;

            // 1. ChessDB.cn (China-hosted 3500+ ELO opening book & 7-piece tablebase)
            coordinatorPool.execute(() -> {
                CloudCandidate res = queryChessDb(fen, legalSet);
                submitCandidate(lock, bestCloud, finishedCloudCount, res);
            });

            // 2. Chess-API.com (Real Stockfish 17 NNUE Depth 18)
            coordinatorPool.execute(() -> {
                CloudCandidate res = queryChessApiSf17(fen, legalSet);
                submitCandidate(lock, bestCloud, finishedCloudCount, res);
            });

            // 3. Stockfish.online (Real Stockfish 16/17 Depth 15)
            coordinatorPool.execute(() -> {
                CloudCandidate res = queryStockfishOnline(fen, legalSet);
                submitCandidate(lock, bestCloud, finishedCloudCount, res);
            });

            // 4. Lichess Cloud Eval (40-ply Stockfish cloud cache)
            coordinatorPool.execute(() -> {
                CloudCandidate res = queryLichessCloud(fen, legalSet);
                submitCandidate(lock, bestCloud, finishedCloudCount, res);
            });

            long startMs = System.currentTimeMillis();
            long maxCloudWaitMs = 3600L;
            CloudCandidate chosen = null;

            synchronized (lock) {
                while (true) {
                    if (latestToken.get() != token) {
                        myStop.set(true);
                        return;
                    }
                    if (bestCloud[0] != null && bestCloud[0].priority >= 100) {
                        chosen = bestCloud[0];
                        break;
                    }
                    long elapsed = System.currentTimeMillis() - startMs;
                    // If we have a solid cloud candidate (e.g. SF15 or ChessDB rank 1), wait at most 450ms for SF17
                    if (bestCloud[0] != null && (elapsed >= 700L || finishedCloudCount.get() >= totalClouds)) {
                        chosen = bestCloud[0];
                        break;
                    }
                    if (finishedCloudCount.get() >= totalClouds || elapsed >= maxCloudWaitMs) {
                        chosen = bestCloud[0];
                        break;
                    }
                    long waitMs = Math.min(200L, maxCloudWaitMs - elapsed);
                    if (waitMs <= 0) break;
                    try {
                        lock.wait(waitMs);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }

            if (latestToken.get() != token) {
                myStop.set(true);
                return;
            }

            if (chosen != null) {
                myStop.set(true);
                callback.onBestMove(token, chosen.uci, chosen.tag);
                return;
            }

            // All cloud engines unreachable/timed out -> use our high-strength ARM64 native engine
            try {
                LocalChessEngine.SearchResult localRes = localFuture.get(2600L, TimeUnit.MILLISECONDS);
                if (latestToken.get() != token) return;
                if (localRes != null && isLegalUci(localRes.uci, legalSet)) {
                    callback.onBestMove(token, localRes.uci, "本地" + localRes.depth + "层");
                    return;
                }
            } catch (Exception ignored) {}

            // Final fallback: signal empty so JS takes immediate legal fallback if needed
            if (latestToken.get() == token) {
                callback.onBestMove(token, "", "本地引擎");
            }
        });
    }

    private void submitCandidate(Object lock, CloudCandidate[] bestCloud, AtomicInteger finishedCount, CloudCandidate c) {
        synchronized (lock) {
            if (c != null) {
                if (bestCloud[0] == null || c.priority > bestCloud[0].priority) {
                    bestCloud[0] = c;
                }
            }
            finishedCount.incrementAndGet();
            lock.notifyAll();
        }
    }

    private boolean isLegalUci(String uci, Set<String> legalSet) {
        if (uci == null || uci.length() < 4) return false;
        if (legalSet.isEmpty()) return true;
        String low = uci.trim().toLowerCase();
        if (legalSet.contains(low)) return true;
        // Also allow matching 4-char base when promotion defaults to queen
        if (low.length() >= 4 && legalSet.contains(low.substring(0, 4))) return true;
        return false;
    }

    private String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private CloudCandidate queryChessDb(String fen, Set<String> legalSet) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://www.chessdb.cn/cdb.php?action=queryall&board="
                    + URLEncoder.encode(fen, "UTF-8") + "&json=1";
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2200);
            conn.setRequestProperty("User-Agent", "ChessSyncOverlay/8.0");
            if (conn.getResponseCode() == 200) {
                String body = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(body);
                if ("ok".equals(json.optString("status"))) {
                    JSONArray moves = json.optJSONArray("moves");
                    if (moves != null && moves.length() > 0) {
                        JSONObject top = moves.getJSONObject(0);
                        String uci = top.optString("uci", "").trim().toLowerCase();
                        int rank = top.optInt("rank", 0);
                        if (isLegalUci(uci, legalSet) && (rank >= 1 || moves.length() == 1)) {
                            int prio = (rank >= 2) ? 105 : 85;
                            return new CloudCandidate(uci, "云库", prio);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private CloudCandidate queryChessApiSf17(String fen, Set<String> legalSet) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://chess-api.com/v1").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2200);
            conn.setReadTimeout(3200);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "ChessSyncOverlay/8.0");

            JSONObject payload = new JSONObject();
            payload.put("fen", fen);
            payload.put("depth", 18);
            payload.put("maxThinkingTime", 100);
            byte[] outBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(outBytes);
            }

            if (conn.getResponseCode() == 200) {
                String body = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(body);
                String uci = json.optString("move", "").trim().toLowerCase();
                int depth = json.optInt("depth", 18);
                if (isLegalUci(uci, legalSet)) {
                    return new CloudCandidate(uci, "SF17·" + depth + "层", 110);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private CloudCandidate queryStockfishOnline(String fen, Set<String> legalSet) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://stockfish.online/api/s/v2.php?fen="
                    + URLEncoder.encode(fen, "UTF-8") + "&depth=15";
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2200);
            conn.setReadTimeout(3200);
            conn.setRequestProperty("User-Agent", "ChessSyncOverlay/8.0");

            if (conn.getResponseCode() == 200) {
                String body = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(body);
                if (json.optBoolean("success", false)) {
                    String best = json.optString("bestmove", "");
                    String[] toks = best.trim().split("\\s+");
                    String uci = (toks.length >= 2 && "bestmove".equals(toks[0])) ? toks[1] : toks[0];
                    uci = uci.trim().toLowerCase();
                    if (isLegalUci(uci, legalSet)) {
                        return new CloudCandidate(uci, "SF·15层", 95);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private CloudCandidate queryLichessCloud(String fen, Set<String> legalSet) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://lichess.org/api/cloud-eval?fen="
                    + URLEncoder.encode(fen, "UTF-8") + "&multiPv=1";
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2400);
            conn.setRequestProperty("User-Agent", "ChessSyncOverlay/8.0");

            if (conn.getResponseCode() == 200) {
                String body = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(body);
                int depth = json.optInt("depth", 30);
                JSONArray pvs = json.optJSONArray("pvs");
                if (pvs != null && pvs.length() > 0) {
                    String movesStr = pvs.getJSONObject(0).optString("moves", "").trim();
                    if (!movesStr.isEmpty()) {
                        String uci = movesStr.split("\\s+")[0].trim().toLowerCase();
                        if (isLegalUci(uci, legalSet)) {
                            return new CloudCandidate(uci, "云评·" + depth + "层", 108);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }
}
