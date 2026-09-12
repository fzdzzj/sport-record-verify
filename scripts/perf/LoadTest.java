import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 并发压测器（压测变更 spec「并发压测方法」：100/500/1000 三档并发 + P95/P99/QPS/错误率）。
 *
 * <p>单文件零依赖（JDK 21 直接运行：{@code java scripts/perf/LoadTest.java --url ... --concurrency 500 --total 2000}）。</p>
 *
 * <p>模型：闭环并发（concurrency 个虚拟线程各自循环发请求，直到达到 total 或 seconds 上限），
 * 与「100/500/1000 并发」口径一致。每请求经共享 {@link HttpClient}（HTTP/1.1 连接池 +
 * 虚拟线程调度）发送；逐请求记录耗时/状态码，产出：</p>
 * <ul>
 *   <li>摘要 JSON：count/qps/errRate/p50/p95/p99/max/limited（HTTP 429 单列，不算错误——
 *       限流拦截验证口径）+ 各状态码分布；</li>
 *   <li>原始 CSV：seq,concurrency,latency_ms,status,ok（留存供优化前后对比，spec 场景「原始数据留存」）。</li>
 * </ul>
 *
 * <p>请求体模板支持占位符替换：{@code {requestId}}（每请求唯一，触发真实落库而非幂等命中）、
 * {@code {userId}}（按 --user-total 轮转分片）、{@code {seq}}（请求序号）。</p>
 */
public class LoadTest {

    /** 单请求记录（配对入 CSV，避免双队列错位） */
    private record Req(int seq, double latencyMs, int status) {
        boolean ok() { return status >= 200 && status < 300; }
    }

    public static void main(String[] args) throws Exception {
        String url = arg(args, "--url", "http://127.0.0.1:8080/record/api/records");
        int concurrency = Integer.parseInt(arg(args, "--concurrency", "100"));
        long total = Long.parseLong(arg(args, "--total", "0"));   // 与 --seconds 二选一，同时给则先到为准
        long seconds = Long.parseLong(arg(args, "--seconds", "0"));
        int warmup = Integer.parseInt(arg(args, "--warmup", "10"));
        String bodyFile = arg(args, "--body", "");
        String outPrefix = arg(args, "--out-prefix", "");          // 摘要/CSV 输出前缀（含目录）
        long userTotal = Long.parseLong(arg(args, "--user-total", "16"));
        int timeoutSec = Integer.parseInt(arg(args, "--timeout", "30"));
        String label = arg(args, "--label", "c" + concurrency);
        String method = arg(args, "--method", "POST");

        if (total <= 0 && seconds <= 0) {
            System.err.println("必须指定 --total N 或 --seconds S 之一");
            System.exit(2);
        }

        String runId = "lt" + System.currentTimeMillis();
        String template = bodyFile.isEmpty() ? "" : Files.readString(Paths.get(bodyFile), StandardCharsets.UTF_8);

        // 请求体预构建（总量模式）：纯字符串替换，避免运行期拼接开销干扰延迟统计
        List<String> bodies;
        if (total > 0) {
            long t0 = System.nanoTime();
            List<String> list = new ArrayList<>((int) total);
            for (long i = 0; i < total; i++) {
                list.add(render(template, runId, i, userTotal));
            }
            bodies = list;
            System.out.printf(Locale.ROOT, "请求体预构建：%d 个，耗时 %.1f ms%n", total, (System.nanoTime() - t0) / 1e6);
        } else {
            bodies = List.of();
        }

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(timeoutSec))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        // 预热：跑通链路（建连、路由、首个 SQL/MQ 通道），不计入统计
        for (int i = 0; i < warmup; i++) {
            sendQuietly(client, method, url,
                    template.isEmpty() ? null : render(template, runId + "w", i, userTotal), timeoutSec);
        }
        System.out.printf(Locale.ROOT, "预热 %d 请求完成，开始压测：concurrency=%d，%s%n",
                warmup, concurrency, total > 0 ? "total=" + total : "seconds=" + seconds);

        // ===== 压测主循环 =====
        AtomicInteger nextSeq = new AtomicInteger(0);
        AtomicLong okCount = new AtomicLong();
        AtomicLong limitedCount = new AtomicLong(); // HTTP 429（限流拦截，单独统计）
        AtomicLong errCount = new AtomicLong();
        ConcurrentLinkedQueue<Req> records = new ConcurrentLinkedQueue<>();
        long wallStart = System.nanoTime();
        CountDownLatch done = new CountDownLatch(concurrency);

        for (int w = 0; w < concurrency; w++) {
            Thread.startVirtualThread(() -> {
                try {
                    while (true) {
                        int seq = nextSeq.getAndIncrement();
                        if (total > 0 && seq >= total) {
                            return;
                        }
                        if (total <= 0) {
                            double elapsedSec = (System.nanoTime() - wallStart) / 1e9;
                            if (elapsedSec >= seconds) {
                                return;
                            }
                        }
                        String body = total > 0 ? bodies.get(seq) : render(template, runId, seq, userTotal);
                        long t0 = System.nanoTime();
                        int status;
                        try {
                            status = sendQuietly(client, method, url, body, timeoutSec);
                        } catch (Exception e) {
                            status = -1; // 连接失败/超时/响应异常
                        }
                        double latencyMs = (System.nanoTime() - t0) / 1e6;
                        records.add(new Req(seq, latencyMs, status));
                        if (status >= 200 && status < 300) {
                            okCount.incrementAndGet();
                        } else if (status == 429) {
                            limitedCount.incrementAndGet();
                        } else {
                            errCount.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        double wallSec = (System.nanoTime() - wallStart) / 1e9;

        // ===== 统计 =====
        List<Req> all = new ArrayList<>(records);
        all.sort(Comparator.comparingInt(Req::seq));
        double[] sorted = all.stream().mapToDouble(Req::latencyMs).sorted().toArray();
        double p50 = pct(sorted, 50), p95 = pct(sorted, 95), p99 = pct(sorted, 99);
        long count = sorted.length;
        double qps = count / wallSec;
        double errRate = count == 0 ? 0 : (errCount.doubleValue() + limitedCount.doubleValue()) / count;

        Map<Integer, Long> statuses = new TreeMap<>();
        all.forEach(r -> statuses.merge(r.status(), 1L, Long::sum));

        System.out.printf(Locale.ROOT,
                "%n===== 压测结果 [%s] concurrency=%d =====%n"
                        + "总请求: %d  成功: %d  限流429: %d  错误: %d  错误率: %.2f%%%n"
                        + "时长: %.2fs  QPS: %.1f%n"
                        + "延迟 ms: P50=%.1f  P95=%.1f  P99=%.1f  MAX=%.1f%n"
                        + "状态码分布: %s%n",
                label, concurrency, count, okCount.get(), limitedCount.get(), errCount.get(), errRate * 100,
                wallSec, qps, p50, p95, p99, sorted.length == 0 ? 0 : sorted[sorted.length - 1], statuses);

        // ===== 产出文件 =====
        if (!outPrefix.isEmpty()) {
            Path summary = Path.of(outPrefix + "-summary.json");
            Path csv = Path.of(outPrefix + "-raw.csv");
            Files.createDirectories(summary.toAbsolutePath().getParent());
            StringBuilder json = new StringBuilder();
            json.append("{\n")
                    .append("  \"label\": \"").append(label).append("\",\n")
                    .append("  \"concurrency\": ").append(concurrency).append(",\n")
                    .append("  \"total\": ").append(count).append(",\n")
                    .append("  \"ok\": ").append(okCount.get()).append(",\n")
                    .append("  \"limited429\": ").append(limitedCount.get()).append(",\n")
                    .append("  \"errors\": ").append(errCount.get()).append(",\n")
                    .append("  \"errRate\": ").append(String.format(Locale.ROOT, "%.4f", errRate)).append(",\n")
                    .append("  \"wallSeconds\": ").append(String.format(Locale.ROOT, "%.3f", wallSec)).append(",\n")
                    .append("  \"qps\": ").append(String.format(Locale.ROOT, "%.2f", qps)).append(",\n")
                    .append("  \"p50ms\": ").append(String.format(Locale.ROOT, "%.2f", p50)).append(",\n")
                    .append("  \"p95ms\": ").append(String.format(Locale.ROOT, "%.2f", p95)).append(",\n")
                    .append("  \"p99ms\": ").append(String.format(Locale.ROOT, "%.2f", p99)).append(",\n")
                    .append("  \"maxMs\": ").append(String.format(Locale.ROOT, "%.2f",
                            sorted.length == 0 ? 0 : sorted[sorted.length - 1])).append(",\n")
                    .append("  \"statusHistogram\": ").append(toJson(statuses)).append("\n")
                    .append("}\n");
            Files.writeString(summary, json.toString(), StandardCharsets.UTF_8);

            StringBuilder csvBody = new StringBuilder("seq,concurrency,latency_ms,status,ok\n");
            for (Req r : all) {
                csvBody.append(r.seq()).append(',').append(concurrency).append(',')
                        .append(String.format(Locale.ROOT, "%.2f", r.latencyMs())).append(',')
                        .append(r.status() < 0 ? "EXCEPTION" : r.status()).append(',')
                        .append(r.ok() ? 1 : 0).append('\n');
            }
            Files.writeString(csv, csvBody.toString(), StandardCharsets.UTF_8);
            System.out.println("已写出：" + summary.toAbsolutePath() + " 与 " + csv.toAbsolutePath());
        }
    }

    /** 发送一个请求，返回 HTTP 状态码（-1 = 异常；异常详情不进统计只打标记） */
    private static int sendQuietly(HttpClient client, String method, String url, String body, int timeoutSec)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json");
        HttpRequest req = switch (method) {
            case "GET" -> b.GET().build();
            case "DELETE" -> b.DELETE().build();
            default -> b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
                    StandardCharsets.UTF_8)).build();
        };
        return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** 占位符替换：{requestId} 唯一（真实落库而非幂等命中），{userId} 轮转（16 分片均匀），{seq} 请求序号 */
    private static String render(String template, String runId, long seq, long userTotal) {
        return template
                .replace("{requestId}", runId + "-" + seq)
                .replace("{userId}", String.valueOf(1 + seq % userTotal))
                .replace("{seq}", String.valueOf(seq));
    }

    /** 百分位（线性插值），sorted 为升序样本 */
    private static double pct(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        double idx = (p / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(idx);
        int hi = Math.min(lo + 1, sorted.length - 1);
        double frac = idx - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    private static String toJson(Map<Integer, Long> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Integer, Long> e : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append('"').append(e.getKey() < 0 ? "EXCEPTION" : e.getKey()).append("\": ").append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String arg(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return def;
    }
}
