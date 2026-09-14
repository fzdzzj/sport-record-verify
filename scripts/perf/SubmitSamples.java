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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 量化验收指标实测器（压测变更 spec「量化达标」：拦截率≥90%、真实通过率≥95%、校验链路 P95<200ms）。
 *
 * <p>单文件零依赖（JDK 21 直接运行：
 * {@code java scripts/perf/SubmitSamples.java --real docs/perf/data/quality-real.jsonl --forged docs/perf/data/quality-forged.jsonl}）。</p>
 *
 * <p>口径：</p>
 * <ul>
 *   <li>拦截率 = 伪造样本中被引擎 REJECTED（verdict=2）的比例（目标 ≥90%）；</li>
 *   <li>真实通过率 = 真实样本中被判 PASSED（verdict=1）的比例（目标 ≥95%）；</li>
 *   <li>校验链路延迟 = 从「提交请求发出」到「判定结果终态（PASSED/REJECTED）可见」的端到端耗时
 *       （含网关+落库+MQ 投递+引擎判定+状态回调全链路，审批版 §8.2 延迟预算 ~160ms 的口径）；
 *       判定本身异步（SUBMITTED 事件消费），故终态通过轮询判定结果接口确认，轮询间隔 100ms
 *       不计入均值——P95 统计该端到端耗时（目标 <200ms）。</li>
 * </ul>
 *
 * <p>提交阶段以小并发（默认 8）压入，避免把「验收集」跑成压力集；结果输出摘要 JSON + 逐样本 CSV。</p>
 *
 * <p>受控速率：默认瞬时压入（与报告基线口径一致）；可传 {@code --rate <perSec>} 把提交摊开到
 * 每秒 perSec 条，用于隔离「突发灌入 → 消费批次排队」导致的尾部延迟（压测报告 §4.2 记录了两口径）。</p>
 */
public class SubmitSamples {

    /** 判定结果里的 verdict 数字（与 Verdict 枚举一致：0 校验中 / 1 通过 / 2 拒绝） */
    private static final int VERDICT_VERIFYING = 0;
    private static final int VERDICT_PASSED = 1;
    private static final int VERDICT_REJECTED = 2;

    private static final Pattern CODE_P = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");
    private static final Pattern RECORD_ID_P = Pattern.compile("\"recordId\"\\s*:\\s*(\\d+)");
    private static final Pattern VERDICT_P = Pattern.compile("\"verdict\"\\s*:\\s*(-?\\d+)");

    /** 单样本结果（配对入 CSV） */
    private record SampleResult(String requestId, String type, long recordId,
                                int verdict, boolean timeout, double submitMs, double e2eMs) {
    }

    public static void main(String[] args) throws Exception {
        String gateway = arg(args, "--gateway", "http://127.0.0.1:8080");
        Path realFile = Paths.get(arg(args, "--real", "docs/perf/data/quality-real.jsonl"));
        Path forgedFile = Paths.get(arg(args, "--forged", "docs/perf/data/quality-forged.jsonl"));
        String outPrefix = arg(args, "--out-prefix", "docs/perf/data/quality-run");
        int submitConcurrency = Integer.parseInt(arg(args, "--submit-concurrency", "8"));
        int timeoutSec = Integer.parseInt(arg(args, "--timeout", "120"));
        // 受控速率（条/秒）：<=0 表示瞬时压入（基线口径）；>0 时把 200 条摊开提交，观察消费调度影响
        double ratePerSec = Double.parseDouble(arg(args, "--rate", "0"));

        List<String> real = Files.readAllLines(realFile, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank()).toList();
        List<String> forged = Files.readAllLines(forgedFile, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank()).toList();
        System.out.printf(Locale.ROOT, "样本加载：真实 %d 条（%s）+ 伪造 %d 条（%s）%n",
                real.size(), realFile, forged.size(), forgedFile);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        // ===== 阶段 1：提交（小并发压入；记录提交耗时） =====
        List<String> allBodies = new ArrayList<>(real.size() + forged.size());
        List<String> allTypes = new ArrayList<>(real.size() + forged.size());
        real.forEach(b -> {
            allBodies.add(b);
            allTypes.add("real");
        });
        forged.forEach(b -> {
            allBodies.add(b);
            allTypes.add("forged");
        });

        long[] recordIds = new long[allBodies.size()];
        double[] submitMs = new double[allBodies.size()];
        int[] submitCodes = new int[allBodies.size()];
        AtomicInteger cursor = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(submitConcurrency);
        CountDownLatch submitted = new CountDownLatch(allBodies.size());
        long submitStart = System.nanoTime();
        final long rateStartNano = submitStart;
        for (int w = 0; w < submitConcurrency; w++) {
            pool.submit(() -> {
                while (true) {
                    int i = cursor.getAndIncrement();
                    if (i >= allBodies.size()) {
                        return;
                    }
                    // 受控速率：第 i 条（0 基）提交不得早于 (i+1)/rate 秒（仅 rate>0 时匀速摊开）
                    if (ratePerSec > 0) {
                        double targetSec = (i + 1) / ratePerSec;
                        long elapsedSec = System.nanoTime() - rateStartNano;
                        long sleepNanos = (long) ((targetSec * 1e9) - elapsedSec);
                        if (sleepNanos > 0) {
                            try {
                                Thread.sleep(sleepNanos / 1_000_000, (int) (sleepNanos % 1_000_000));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                    long t0 = System.nanoTime();
                    int code = -1;
                    long recordId = -1;
                    try {
                        HttpRequest req = HttpRequest.newBuilder(URI.create(gateway + "/record/api/records"))
                                .timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(allBodies.get(i), StandardCharsets.UTF_8))
                                .build();
                        String resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
                        code = intOf(CODE_P, resp);
                        recordId = longOf(RECORD_ID_P, resp);
                    } catch (Exception ignored) {
                        // 提交失败：recordId 保持 -1，阶段 2 按失败样本统计
                    }
                    submitMs[i] = (System.nanoTime() - t0) / 1e6;
                    recordIds[i] = recordId;
                    submitCodes[i] = code;
                    submitted.countDown();
                }
            });
        }
        submitted.await();
        double submitWallSec = (System.nanoTime() - submitStart) / 1e9;
        pool.shutdown();
        long submitFailed = 0;
        for (int c : submitCodes) {
            if (c != 0 && c != 3004) {
                submitFailed++;
            }
        }
        System.out.printf(Locale.ROOT, "提交完成：%.2fs（提交失败 %d 条）%s%n", submitWallSec, submitFailed,
                ratePerSec > 0 ? String.format(Locale.ROOT, "，受控速率 %.0f 条/秒", ratePerSec) : "，瞬时压入（基线口径）");

        // ===== 阶段 2：轮询终判（校验异步：SUBMITTED 事件 → 引擎 → 回调） =====
        ConcurrentLinkedQueue<SampleResult> results = new ConcurrentLinkedQueue<>();
        ExecutorService pollPool = Executors.newFixedThreadPool(16);
        CountDownLatch polled = new CountDownLatch(allBodies.size());
        for (int i = 0; i < allBodies.size(); i++) {
            final int idx = i;
            pollPool.submit(() -> {
                String type = allTypes.get(idx);
                String requestId = stringOf(Pattern.compile("\"requestId\"\\s*:\\s*\"([^\"]+)\""), allBodies.get(idx));
                long recordId = recordIds[idx];
                if (recordId <= 0) {
                    results.add(new SampleResult(requestId, type, recordId, -1, true, submitMs[idx], -1));
                    polled.countDown();
                    return;
                }
                long t0 = System.nanoTime();
                long deadline = t0 + TimeUnit.SECONDS.toNanos(timeoutSec);
                int verdict = -1;
                try {
                    while (System.nanoTime() < deadline) {
                        HttpRequest req = HttpRequest.newBuilder(
                                        URI.create(gateway + "/record/api/records/" + recordId + "/verify-result"))
                                .timeout(Duration.ofSeconds(5))
                                .GET().build();
                        String resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
                        Integer v = intOrNull(VERDICT_P, resp);
                        if (v != null && v != VERDICT_VERIFYING) {
                            verdict = v;
                            break;
                        }
                        Thread.sleep(100); // 轮询间隔不计入端到端延迟统计
                    }
                } catch (Exception ignored) {
                    verdict = -1;
                }
                boolean timeout = verdict != VERDICT_PASSED && verdict != VERDICT_REJECTED;
                double e2e = timeout ? -1 : (System.nanoTime() - t0) / 1e6;
                results.add(new SampleResult(requestId, type, recordId, verdict, timeout, submitMs[idx], e2e));
                polled.countDown();
            });
        }
        polled.await();
        pollPool.shutdown();

        // ===== 汇总 =====
        List<SampleResult> sorted = new ArrayList<>(results.stream()
                .sorted((a, b) -> a.requestId().compareTo(b.requestId())).toList());
        long realTotal = 0, realPassed = 0, realOther = 0;
        long forgedTotal = 0, forgedRejected = 0, forgedOther = 0;
        List<Double> e2eAll = new ArrayList<>();
        for (SampleResult r : sorted) {
            if ("real".equals(r.type())) {
                realTotal++;
                if (r.verdict() == VERDICT_PASSED) {
                    realPassed++;
                } else {
                    realOther++;
                }
            } else {
                forgedTotal++;
                if (r.verdict() == VERDICT_REJECTED) {
                    forgedRejected++;
                } else {
                    forgedOther++;
                }
            }
            if (r.e2eMs() >= 0) {
                e2eAll.add(r.e2eMs());
            }
        }
        double interceptRate = forgedTotal == 0 ? 0 : forgedRejected * 100.0 / forgedTotal;
        double passRate = realTotal == 0 ? 0 : realPassed * 100.0 / realTotal;
        double[] e2eSorted = e2eAll.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double p50 = pct(e2eSorted, 50), p95 = pct(e2eSorted, 95), p99 = pct(e2eSorted, 99);

        System.out.printf(Locale.ROOT,
                "%n===== 量化验收结果 =====%n"
                        + "拦截率（伪造→REJECTED）: %d/%d = %.2f%%   （目标 ≥90%%）%n"
                        + "真实通过率（真实→PASSED）: %d/%d = %.2f%%   （目标 ≥95%%）%n"
                        + "校验链路端到端 ms: P50=%.1f  P95=%.1f  P99=%.1f  MAX=%.1f  n=%d   （目标 P95<200ms）%n"
                        + "未终判（超时/提交失败）: 真实 %d 条 / 伪造 %d 条%n",
                forgedRejected, forgedTotal, interceptRate,
                realPassed, realTotal, passRate,
                p50, p95, p99,
                e2eSorted.length == 0 ? 0 : e2eSorted[e2eSorted.length - 1], e2eSorted.length,
                realOther, forgedOther);

        // ===== 产出文件 =====
        Path summary = Paths.get(outPrefix + "-summary.json");
        Files.createDirectories(summary.toAbsolutePath().getParent());
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"realTotal\": ").append(realTotal).append(",\n")
                .append("  \"realPassed\": ").append(realPassed).append(",\n")
                .append("  \"realNotPassed\": ").append(realOther).append(",\n")
                .append("  \"passRate\": ").append(fmt(passRate)).append(",\n")
                .append("  \"forgedTotal\": ").append(forgedTotal).append(",\n")
                .append("  \"forgedRejected\": ").append(forgedRejected).append(",\n")
                .append("  \"forgedNotRejected\": ").append(forgedOther).append(",\n")
                .append("  \"interceptRate\": ").append(fmt(interceptRate)).append(",\n")
                .append("  \"e2eCount\": ").append(e2eSorted.length).append(",\n")
                .append("  \"e2eP50ms\": ").append(fmt(p50)).append(",\n")
                .append("  \"e2eP95ms\": ").append(fmt(p95)).append(",\n")
                .append("  \"e2eP99ms\": ").append(fmt(p99)).append(",\n")
                .append("  \"e2eMaxMs\": ").append(fmt(e2eSorted.length == 0 ? 0 : e2eSorted[e2eSorted.length - 1])).append("\n")
                .append("}\n");
        Files.writeString(summary, json.toString(), StandardCharsets.UTF_8);

        Path csv = Paths.get(outPrefix + "-detail.csv");
        StringBuilder csvBody = new StringBuilder("requestId,type,recordId,verdict,timeout,submit_ms,e2e_ms\n");
        for (SampleResult r : sorted) {
            csvBody.append(r.requestId()).append(',').append(r.type()).append(',')
                    .append(r.recordId()).append(',')
                    .append(r.verdict() < 0 ? "SUBMIT_FAIL" : r.verdict()).append(',')
                    .append(r.timeout() ? 1 : 0).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", r.submitMs())).append(',')
                    .append(r.e2eMs() < 0 ? "NA" : String.format(Locale.ROOT, "%.2f", r.e2eMs())).append('\n');
        }
        Files.writeString(csv, csvBody.toString(), StandardCharsets.UTF_8);
        System.out.println("已写出：" + summary.toAbsolutePath() + " 与 " + csv.toAbsolutePath());
    }

    // ==================== 小工具 ====================

    private static int send(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static Integer intOrNull(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static int intOf(Pattern p, String s) {
        Integer v = intOrNull(p, s);
        return v == null ? -1 : v;
    }

    private static long longOf(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static String stringOf(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "unknown";
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

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

    private static String arg(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return def;
    }
}
