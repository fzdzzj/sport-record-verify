import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 压测样本生成器（压测变更 spec「环境可复现」+「测试集 ≥200 条正负各半」）。
 *
 * <p>单文件零依赖（JDK 21 直接运行：{@code java scripts/perf/GenSamples.java --out-dir docs/perf/data}）。</p>
 *
 * <p>产出三类文件：</p>
 * <ul>
 *   <li>{@code quality-real.jsonl}    正样本 ×100：真实运动轨迹（慢跑/快走/变速跑/骑行四画像轮转，
 *       跑步 2.2~4.5 m/s、骑行 6.4~7.6 m/s，均单向路线，预期全部 PASSED——构造避开 R1 速度/
 *       R3 停留/R4 折返阈值）；</li>
 *   <li>{@code quality-forged.jsonl}  负样本 ×100：五类伪造模式轮转（匀速刷里程 R1、折返刷里程 R4、
 *       停留刷时长 R3、GPS 漂移污染、加速度突变 R2），预期全部 REJECTED；</li>
 *   <li>{@code load-template.json}    并发压测请求体模板：300 点真实轨迹，{@code {requestId}} 与
 *       {@code {userId}} 占位符由 LoadTest 按请求替换。</li>
 * </ul>
 *
 * <p>判定规则口径（审批版 §5.2 默认阈值）：R1 窗口均速&gt;5.5m/s 持续≥10 窗口（HARD）、
 * R2 加速度&gt;3m/s² ≥3 次（HARD）、R3 停留占比&gt;40%（HARD）、R4 路径/直线比&gt;3（SOFT，
 * 默认 soft-only-reject=true 也拒绝）、预处理漂移占比&gt;30%（SOFT）。
 * 注意两点构造约束（报告「已知边界」一节同步说明）：</p>
 * <ul>
 *   <li>真实样本不能是环形/折返路线——R4 对起点≈终点直接判无穷大，真实闭环跑会被误拦；</li>
 *   <li>骑行画像（6~8 m/s）按 sportType=CYCLING 提交：引擎阈值已按运动类型分维度
 *       （CYCLING 速度上限 15，见 ADR-0004 §5），骑行正常速度不再被 R1 误杀——
 *       此前引擎阈值不分类型，正样本集被迫只取跑步/快走口径（本变更已消除该局限）。</li>
 * </ul>
 */
public class GenSamples {

    /** 轨迹基准起点（上海人民广场附近，任意真实城区即可） */
    private static final double BASE_LAT = 31.2304;
    private static final double BASE_LNG = 121.4737;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public static void main(String[] args) throws IOException {
        Path outDir = Paths.get(arg(args, "--out-dir", "docs/perf/data"));
        int qualityCount = Integer.parseInt(arg(args, "--quality", "200")); // 正负各半
        int points = Integer.parseInt(arg(args, "--points", "300"));
        Files.createDirectories(outDir);

        long seedBase = System.currentTimeMillis();
        int realCount = qualityCount / 2;
        int forgedCount = qualityCount - realCount;

        // ===== 正样本：真实轨迹（四画像轮转，均单向路线，带运动类型）=====
        List<String> real = new ArrayList<>(realCount);
        for (int i = 0; i < realCount; i++) {
            // 画像：0=慢跑 3.2m/s、1=快走 1.6m/s、2=变速跑 2.2~4.5m/s（平滑渐变，不超 5.5）、
            //       3=骑行 7.0m/s（平滑渐变 6.4~7.6，按 CYCLING 类型提交——阈值上限 15 不误杀）
            // 类型映射：0/2 RUNNING，1 WALKING，3 CYCLING
            int sportType = switch (i % 4) {
                case 1 -> 3;      // WALKING
                case 3 -> 2;      // CYCLING
                default -> 1;     // RUNNING
            };
            Track t = switch (i % 4) {
                case 0 -> realTrack(points, 3.2, 0.35, seedBase + i);
                case 1 -> realTrack(points, 1.6, 0.20, seedBase + i);
                case 2 -> realTrack(points, 3.3, 1.10, seedBase + i);
                default -> realTrack(points, 7.0, 0.60, seedBase + i); // 骑行
            };
            real.add(toSubmitJson(t, "qual-r-" + (seedBase % 100000) + "-" + i, 1 + (i % 16), sportType));
        }
        writeLines(outDir.resolve("quality-real.jsonl"), real);

        // ===== 负样本：五类伪造模式轮转 =====
        List<String> forged = new ArrayList<>(forgedCount);
        String[] modeNames = {"R1_SPEED_SPIKE", "R4_LOOP", "R3_STAY", "PRE_DRIFT", "R2_ACCEL"};
        for (int i = 0; i < forgedCount; i++) {
            String mode = modeNames[i % modeNames.length];
            Track t = switch (mode) {
                case "R1_SPEED_SPIKE" -> forgedSpeedSpike(points, seedBase + i);
                case "R4_LOOP"        -> forgedLoop(points, seedBase + i);
                case "R3_STAY"        -> forgedStay(points, seedBase + i);
                case "PRE_DRIFT"      -> forgedDrift(points, seedBase + i);
                default               -> forgedAccel(points, seedBase + i);
            };
            forged.add(toSubmitJson(t, "qual-f-" + (seedBase % 100000) + "-" + i, 1 + (i % 16), 1));
        }
        writeLines(outDir.resolve("quality-forged.jsonl"), forged);

        // ===== 并发压测请求体模板（真实轨迹 + requestId/userId 占位符，RUNNING 口径）=====
        Track t = realTrack(points, 3.2, 0.35, seedBase);
        String template = toSubmitJson(t, "{requestId}", "{userId}", 1);
        Files.writeString(outDir.resolve("load-template.json"), template, StandardCharsets.UTF_8);

        System.out.printf(Locale.ROOT,
                "样本生成完成：正样本 %d 条 → %s / 负样本 %d 条 → %s / 压测模板（%d 点）→ load-template.json%n",
                realCount, outDir.resolve("quality-real.jsonl"), forgedCount,
                outDir.resolve("quality-forged.jsonl"), points);
    }

    // ==================== 真实轨迹（正样本） ====================

    /**
     * 单向慢跑/快走轨迹：速度围绕均值小幅平滑波动（正弦 + 抖动），逐点东向推进；
     * 距离=逐点 haversine 累计，保证 R4 比值 ≈1.0（远低于 3.0）。
     */
    private static Track realTrack(int points, double avgSpeed, double wobble, long seed) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double lat = BASE_LAT, lng = BASE_LNG;
        long startTs = Instant.now().toEpochMilli() - 86_400_000L; // 昨天此刻，避免与实时提交撞窗口
        long dtMs = 6000; // 6s 采样（300 点 ≈ 30 分钟 ≈ 5~6km）
        Track t = new Track(points);
        double phase = (seed % 360) / 57.29578;
        for (int i = 0; i < points; i++) {
            // 平滑变速：均值 + 正弦摆动 + 小抖动（加速度峰值 << 3 m/s²）
            double speed = avgSpeed + wobble * Math.sin(phase + i / 12.0) + rnd.nextDouble(-0.08, 0.08);
            speed = Math.max(0.8, speed);
            t.addPoint(lat, lng, startTs + (long) i * dtMs, speed, i);
            double[] next = step(lat, lng, speed * dtMs / 1000.0, 0.9); // 基本正东，轻微偏移
            lat = next[0];
            lng = next[1];
        }
        return t;
    }

    // ==================== 伪造轨迹（负样本） ====================

    /** 模式一（R1 HARD）：全程匀速 8 m/s「骑车刷里程」——窗口均速持续超 5.5，且低于 20 不触发漂移剔除 */
    private static Track forgedSpeedSpike(int points, long seed) {
        return linearTrack(points, 8.0, seed);
    }

    /** 模式二（R4 SOFT，soft-only-reject 默认拒绝）：出去 2km 折返回起点——起点≈终点比值无穷大 */
    private static Track forgedLoop(int points, long seed) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double lat = BASE_LAT, lng = BASE_LNG;
        long startTs = Instant.now().toEpochMilli() - 86_400_000L;
        long dtMs = 6000;
        Track t = new Track(points);
        for (int i = 0; i < points; i++) {
            double speed = 3.2 + rnd.nextDouble(-0.1, 0.1); // 每段速度正常，仅路线折返
            t.addPoint(lat, lng, startTs + (long) i * dtMs, speed, i);
            double dist = speed * dtMs / 1000.0;
            double[] next = (i < points / 2) ? step(lat, lng, dist, 1.2) // 前 150 点向东
                                             : step(lat, lng, dist, 1.2 + Math.PI); // 后 150 点向西（原路返回）
            lat = next[0];
            lng = next[1];
        }
        return t;
    }

    /** 模式三（R3 HARD）：60% 点原地停留（位移 <5m、单段 ≥5 分钟）——停留占比超 40% */
    private static Track forgedStay(int points, long seed) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double lat = BASE_LAT, lng = BASE_LNG;
        long startTs = Instant.now().toEpochMilli() - 86_400_000L;
        long dtMs = 6000;
        Track t = new Track(points);
        for (int i = 0; i < points; i++) {
            boolean stay = (i / 60) % 2 == 0; // 60 点停留（6 分钟）/ 60 点移动交替，停留占比 ~50%
            if (stay) {
                // 停留：3.5m 内漂移（< maxMeters=5），速度报 0
                double dLat = rnd.nextDouble(-0.000015, 0.000015);
                double dLng = rnd.nextDouble(-0.000015, 0.000015);
                t.addPoint(lat + dLat, lng + dLng, startTs + (long) i * dtMs, 0.0, i);
            } else {
                t.addPoint(lat, lng, startTs + (long) i * dtMs, 1.5, i);
                double[] next = step(lat, lng, 1.5 * dtMs / 1000.0, 0.9);
                lat = next[0];
                lng = next[1];
            }
        }
        return t;
    }

    /** 模式四（预处理 SOFT）：40% 点 GPS 瞬移（单点速度 25~60 m/s）→ 漂移剔除占比超 30% */
    private static Track forgedDrift(int points, long seed) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double lat = BASE_LAT, lng = BASE_LNG;
        long startTs = Instant.now().toEpochMilli() - 86_400_000L;
        long dtMs = 6000;
        Track t = new Track(points);
        for (int i = 0; i < points; i++) {
            if (i % 5 < 2) { // 40% 漂移点：6s 内跳 150~360m → v=25~60 m/s（> vDrift=20 被剔除）
                double jumpM = rnd.nextDouble(150, 360);
                double[] next = step(lat, lng, jumpM, rnd.nextDouble(0, 6.28));
                lat = next[0];
                lng = next[1];
                t.addPoint(lat, lng, startTs + (long) i * dtMs, 40.0, i);
            } else {
                t.addPoint(lat, lng, startTs + (long) i * dtMs, 2.5, i);
                double[] next = step(lat, lng, 2.5 * dtMs / 1000.0, 0.9);
                lat = next[0];
                lng = next[1];
            }
        }
        return t;
    }

    /** 模式五（R2 HARD）：1s 采样，1/9 m/s 交替——|Δv|/Δt = 8 m/s² 远超 3，且快段仅 3 点不触发 R1 */
    private static Track forgedAccel(int points, long seed) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double lat = BASE_LAT, lng = BASE_LNG;
        long startTs = Instant.now().toEpochMilli() - 86_400_000L;
        long dtMs = 1000;
        Track t = new Track(points);
        for (int i = 0; i < points; i++) {
            double speed = (i % 6 < 3) ? 1.0 : 9.0; // 3s 慢走 / 3s 猜测外挂加速，硬切
            t.addPoint(lat, lng, startTs + (long) i * dtMs, speed + rnd.nextDouble(-0.05, 0.05), i);
            double[] next = step(lat, lng, speed * dtMs / 1000.0, 0.9);
            lat = next[0];
            lng = next[1];
        }
        return t;
    }

    /** 匀速直线轨迹（速度可配，伪造「匀速刷里程」基型） */
    private static Track linearTrack(int points, double speed, long seed) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double lat = BASE_LAT, lng = BASE_LNG;
        long startTs = Instant.now().toEpochMilli() - 86_400_000L;
        long dtMs = 6000;
        Track t = new Track(points);
        for (int i = 0; i < points; i++) {
            t.addPoint(lat, lng, startTs + (long) i * dtMs, speed + rnd.nextDouble(-0.02, 0.02), i);
            double[] next = step(lat, lng, speed * dtMs / 1000.0, 0.9);
            lat = next[0];
            lng = next[1];
        }
        return t;
    }

    // ==================== 几何与序列化工具 ====================

    /** 从 (lat,lng) 沿 bearing 方向前进 meters，返回新坐标（球面近似） */
    private static double[] step(double lat, double lng, double meters, double bearingRad) {
        double dLat = (meters * Math.cos(bearingRad)) / 111_320.0;
        double dLng = (meters * Math.sin(bearingRad)) / (111_320.0 * Math.cos(Math.toRadians(lat)));
        return new double[]{lat + dLat, lng + dLng};
    }

    /** 提交体 JSON（字段与 RecordSubmitDTO 对齐；LocalDateTime 用本地时区格式；sportType 按画像带类型） */
    private static String toSubmitJson(Track t, String requestId, Object userId, int sportType) {
        LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.firstTs), ZoneOffset.UTC);
        LocalDateTime end = LocalDateTime.ofInstant(Instant.ofEpochMilli(t.lastTs), ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(64 + t.size * 96);
        sb.append("{\"requestId\":\"").append(requestId).append('"')
          .append(",\"userId\":").append(userId)
          .append(",\"sportType\":").append(sportType)
          .append(",\"startTime\":\"").append(TS_FMT.format(start)).append('"')
          .append(",\"endTime\":\"").append(TS_FMT.format(end)).append('"')
          .append(",\"distance\":").append(BigDecimal.valueOf(t.pathKm).setScale(2, RoundingMode.HALF_UP))
          .append(",\"duration\":").append((t.lastTs - t.firstTs) / 1000)
          .append(",\"points\":[");
        for (int i = 0; i < t.size; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"seq\":").append(i)
              .append(",\"lat\":").append(t.lats[i])
              .append(",\"lng\":").append(t.lngs[i])
              .append(",\"ts\":").append(t.tss[i])
              .append(",\"speed\":").append(t.speeds[i]).append('}');
        }
        return sb.append("]}").toString();
    }

    private static void writeLines(Path path, List<String> lines) throws IOException {
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static String arg(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return def;
    }

    /** 轨迹累加器：逐点累计 haversine 路径长度（与引擎口径一致） */
    @SuppressWarnings("unused")
    private static final class Track {
        final int size;
        final double[] lats;
        final double[] lngs;
        final long[] tss;
        final double[] speeds;
        double pathKm = 0;
        long firstTs;
        long lastTs;
        private double prevLat = Double.NaN;
        private double prevLng = Double.NaN;

        Track(int size) {
            this.size = size;
            this.lats = new double[size];
            this.lngs = new double[size];
            this.tss = new long[size];
            this.speeds = new double[size];
        }

        void addPoint(double lat, double lng, long ts, double speed, int idx) {
            lats[idx] = round6(lat);
            lngs[idx] = round6(lng);
            tss[idx] = ts;
            speeds[idx] = round2(speed);
            if (idx == 0) {
                firstTs = ts;
            }
            lastTs = ts;
            if (!Double.isNaN(prevLat)) {
                pathKm += Geo.havKm(prevLat, prevLng, lats[idx], lngs[idx]);
            }
            prevLat = lats[idx];
            prevLng = lngs[idx];
        }

        private static double round6(double v) {
            return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).doubleValue();
        }

        private static double round2(double v) {
            return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
        }
    }

    /** haversine（与 verify-service GeoUtils 同公式，保证样本距离口径一致） */
    private static final class Geo {
        static double havKm(double lat1, double lng1, double lat2, double lng2) {
            double R = 6371000.0;
            double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
            double dp = p2 - p1, dl = Math.toRadians(lng2 - lng1);
            double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                    + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
            return 2 * R * Math.asin(Math.sqrt(a)) / 1000.0;
        }
    }
}
