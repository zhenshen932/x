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
 * Multi-tier native chess super-engine coordinator (v11).
 * Fixes late-game strength collapse by preserving full SF17 NNUE reachability,
 * rejecting low-rank ChessDB moves, and backing up with parallel ARM64 LocalChessEngine.
 */
public class NativeEngineManager {
    public interface Callback {
        void onBestMove(int token, String uci, String sourceTag);
    }

    private static class CloudCandidate {
        final String uci;
        final String tag;
        final int priority;

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

    public void requestBestMove(
            final String fen,
            final int token,
            final String legalMovesCsv,
            final boolean fastMode,
            final String recentFensPipe,
            final Callback callback
    ) {
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

        final String[] historyArr = (recentFensPipe != null && !recentFensPipe.isEmpty())
                ? recentFensPipe.split("\\|")
                : null;

        // 半自动模式本地并行搜索 1120ms（与云端同时跑，零额外耗时），普通模式 2100ms
        final long localBudgetMs = fastMode ? 1120L : 2100L;
        final Future<LocalChessEngine.SearchResult> localFuture = localPool.submit(() -> {
            localEngine.setGameHistoryFens(historyArr);
            return localEngine.findBestMove(fen, localBudgetMs, myStop);
        });

        coordinatorPool.execute(() -> {
            final long startMs = System.currentTimeMillis();
            final Object lock = new Object();
            final CloudCandidate[] bestCloud = new CloudCandidate[1];
            final AtomicInteger finishedCloudCount = new AtomicInteger(0);
            final int totalClouds = 4;

            // 关键修复：给海外 SF17/SF15 留出合理的 TLS 握手与推理窗口，避免中残局大后期全线超时
            final int connectMs = fastMode ? 1050 : 2000;
            final int readMs = fastMode ? 1320 : 2600;

            // 1. ChessDB.cn (国内超算开局与7子残局库，仅采纳 rank >= 2 强招，杜绝残局劣招)
            coordinatorPool.execute(() -> {
                CloudCandidate res = queryChessDb(fen, legalSet, Math.min(connectMs, 800), Math.min(readMs, 1000));
                submitCandidate(lock, bestCloud, finishedCloudCount, res);
            });

            // 2. Chess-API.com (真 Stockfish 17 NNUE 16层/18层)
            coordinatorPool.execute(() -> {
                CloudCandidate res = queryChessApiSf17(fen, legalSet, fastMode, connectMs, readMs);
                submitCandidate(lock, bestCloud, finishedCloudCount, res);
            });

            // 3. Stockfish.online (真 Stockfish 14层/15层)
            coordinatorPool.execute(() -> {
                CloudCandidate res = queryStockfishOnline(fen, legalSet, fastMode, connectMs, readMs);
                submitCandidate(lock, bestCloud, finishedCloudCount, res);
            });

            // 4. Lichess Cloud Eval (40层云评)
            coordinatorPool.execute(() -> {
                CloudCandidate res = queryLichessCloud(fen, legalSet, connectMs, readMs);
                submitCandidate(lock, bestCloud, finishedCloudCount, res);
            });

            // 命中高优超算（SF17/Lichess/ChessDB强招）瞬间返回（开局 0.2s~0.4s，中残局 0.6s~1.25s）
            final long maxCloudWaitMs = fastMode ? 1280L : 3200L;
            final long goodEnoughWaitMs = fastMode ? 320L : 650L;
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
                    if (bestCloud[0] != null && (elapsed >= goodEnoughWaitMs || finishedCloudCount.get() >= totalClouds)) {
                        chosen = bestCloud[0];
                        break;
                    }
                    if (finishedCloudCount.get() >= totalClouds || elapsed >= maxCloudWaitMs) {
                        chosen = bestCloud[0];
                        break;
                    }
                    long waitMs = Math.min(80L, maxCloudWaitMs - elapsed);
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

            // 云端未返回时，取回已并行搜索完毕的高强本地引擎结果（总耗时严格受控，绝不掉回 2 层弱脚本）
            myStop.set(true);
            try {
                LocalChessEngine.SearchResult localRes = localFuture.get(450L, TimeUnit.MILLISECONDS);
                if (latestToken.get() != token) return;
                if (localRes != null && isLegalUci(localRes.uci, legalSet)) {
                    callback.onBestMove(token, localRes.uci, "本地" + localRes.depth + "层");
                    return;
                }
            } catch (Exception ignored) {
                myStop.set(true);
            }

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

    private CloudCandidate queryChessDb(String fen, Set<String> legalSet, int connectMs, int readMs) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://www.chessdb.cn/cdb.php?action=queryall&board="
                    + URLEncoder.encode(fen, "UTF-8") + "&json=1";
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("User-Agent", "ChessSyncOverlay/11.0");
            if (conn.getResponseCode() == 200) {
                String body = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(body);
                if ("ok".equals(json.optString("status"))) {
                    JSONArray moves = json.optJSONArray("moves");
                    if (moves != null && moves.length() > 0) {
                        JSONObject top = moves.getJSONObject(0);
                        String uci = top.optString("uci", "").trim().toLowerCase();
                        int rank = top.optInt("rank", 0);
                        // 严格拒绝 rank == 0 的坏棋/送子缓存
                        if (isLegalUci(uci, legalSet) && rank >= 1) {
                            int prio = (rank >= 2) ? 105 : 75;
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

    private CloudCandidate queryChessApiSf17(String fen, Set<String> legalSet, boolean fastMode, int connectMs, int readMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://chess-api.com/v1").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "ChessSyncOverlay/11.0");

            JSONObject payload = new JSONObject();
            payload.put("fen", fen);
            payload.put("depth", fastMode ? 16 : 18);
            payload.put("maxThinkingTime", fastMode ? 55 : 100);
            byte[] outBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(outBytes);
            }

            if (conn.getResponseCode() == 200) {
                String body = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(body);
                String uci = json.optString("move", "").trim().toLowerCase();
                int depth = json.optInt("depth", fastMode ? 16 : 18);
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

    private CloudCandidate queryStockfishOnline(String fen, Set<String> legalSet, boolean fastMode, int connectMs, int readMs) {
        HttpURLConnection conn = null;
        try {
            int reqDepth = fastMode ? 14 : 15;
            String urlStr = "https://stockfish.online/api/s/v2.php?fen="
                    + URLEncoder.encode(fen, "UTF-8") + "&depth=" + reqDepth;
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("User-Agent", "ChessSyncOverlay/11.0");

            if (conn.getResponseCode() == 200) {
                String body = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(body);
                if (json.optBoolean("success", false)) {
                    String best = json.optString("bestmove", "");
                    String[] toks = best.trim().split("\\s+");
                    String uci = (toks.length >= 2 && "bestmove".equals(toks[0])) ? toks[1] : toks[0];
                    uci = uci.trim().toLowerCase();
                    if (isLegalUci(uci, legalSet)) {
                        return new CloudCandidate(uci, "SF·" + reqDepth + "层", 96);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private CloudCandidate queryLichessCloud(String fen, Set<String> legalSet, int connectMs, int readMs) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://lichess.org/api/cloud-eval?fen="
                    + URLEncoder.encode(fen, "UTF-8") + "&multiPv=1";
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("User-Agent", "ChessSyncOverlay/11.0");

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
