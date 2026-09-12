import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OSM 路网切片导入器（零依赖 JDK21 单文件程序，与 scripts/perf/GenSamples.java 同套路）。
 *
 * <p>输入：Overpass API 下载的 OSM XML 路网切片（node + way）；
 * 输出：stdout 打印 road_edge 表的批量 INSERT SQL，由导入脚本经
 * {@code docker exec psql} 灌入 PostGIS。用法：{@code java OsmImporter.java <osm文件>}。</p>
 *
 * <p>为什么自写解析器而不用 osm2pgsql：osm2pgsql 面向国家级全量数据，产出表结构
 * （planet_osm_line 等）冗余且需额外镜像；单城市切片只有 node/way 两类元素，
 * StAX 流式解析 + 多值 INSERT 就够了，仓库零新增依赖、脚本可读可改（见 ADR-0006）。</p>
 */
public class OsmImporter {

    /** OSM highway 标签 → 道路等级（1=高速/快速 2=主干 3=次干 4=支路/居住 5=步行/骑行） */
    private static final Map<String, Integer> ROAD_LEVEL = Map.ofEntries(
            Map.entry("motorway", 1), Map.entry("motorway_link", 1),
            Map.entry("trunk", 1), Map.entry("trunk_link", 1),
            Map.entry("primary", 2), Map.entry("primary_link", 2),
            Map.entry("secondary", 3), Map.entry("secondary_link", 3),
            Map.entry("tertiary", 3), Map.entry("tertiary_link", 3),
            Map.entry("unclassified", 4), Map.entry("residential", 4),
            Map.entry("living_street", 4), Map.entry("service", 4), Map.entry("road", 4),
            Map.entry("pedestrian", 5), Map.entry("footway", 5),
            Map.entry("cycleway", 5), Map.entry("path", 5));

    /** 每个 INSERT 语句容纳的 way 行数（多值 INSERT 减少 psql 往返，几百行一批够快） */
    private static final int BATCH_SIZE = 400;

    public static void main(String[] args) throws Exception {
        // stdout 强制 UTF-8：Windows 上 System.out 默认跟随控制台编码（GBK），中文路名会写出
        // GBK 字节，psql 侧按 UTF8 校验直接报 invalid byte sequence（实测踩坑 0xc4 0xcf）
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        if (args.length < 1) {
            System.err.println("用法：java OsmImporter.java <osm切片文件.xml>");
            System.exit(2);
        }
        // StAX 流式解析：node 全量进内存（单城市切片十万点级，内存可控），
        // way 先攒元数据与 nd 引用序列，解析完成后统一生成 SQL（way 引用的 node 必已入表）
        Map<Long, double[]> nodes = new HashMap<>(1 << 18);   // nodeId -> [lat, lon]
        List<String> wayIds = new ArrayList<>();
        List<String> wayNames = new ArrayList<>();
        List<String> wayTypes = new ArrayList<>();
        List<List<Long>> wayRefs = new ArrayList<>();
        long skippedWay = 0;                                  // 因白名单外 highway/引用缺失被跳过的 way 数

        try (FileInputStream fis = new FileInputStream(args[0])) {
            XMLStreamReader r = XMLInputFactory.newInstance().createXMLStreamReader(
                    new InputStreamReader(fis, StandardCharsets.UTF_8));
            String curWayId = null, curName = null, curHighway = null;
            List<Long> curRefs = null;
            for (int ev = r.next(); ev != XMLStreamConstants.END_DOCUMENT; ev = r.next()) {
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "node" -> nodes.put(Long.parseLong(r.getAttributeValue(null, "id")),
                                new double[]{Double.parseDouble(r.getAttributeValue(null, "lat")),
                                        Double.parseDouble(r.getAttributeValue(null, "lon"))});
                        case "way" -> {
                            curWayId = r.getAttributeValue(null, "id");
                            curName = null;
                            curHighway = null;
                            curRefs = new ArrayList<>();
                        }
                        case "nd" -> {
                            if (curRefs != null) {
                                curRefs.add(Long.parseLong(r.getAttributeValue(null, "ref")));
                            }
                        }
                        case "tag" -> {
                            // 只在 way 上下文取标签（node 上也可能挂 tag，与路网无关）
                            if (curWayId == null) {
                                break;
                            }
                            String k = r.getAttributeValue(null, "k"), v = r.getAttributeValue(null, "v");
                            if ("name".equals(k)) {
                                curName = v;
                            } else if ("highway".equals(k) && ROAD_LEVEL.containsKey(v)) {
                                curHighway = v;   // 只收白名单类型：机动车道 + 步行/骑行道，排除 construction 等噪音
                            }
                        }
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && "way".equals(r.getLocalName())) {
                    // way 结束：有 highway 标签且顶点齐全才收（Overpass 切片边界处可能缺 node）
                    if (curWayId != null) {
                        if (curHighway != null && curRefs.size() >= 2 && nodes.keySet().containsAll(curRefs)) {
                            wayIds.add(curWayId);
                            wayNames.add(curName == null ? "" : curName.replace("'", "''"));
                            wayTypes.add(curHighway);
                            wayRefs.add(curRefs);
                        } else {
                            skippedWay++;
                        }
                    }
                    curWayId = null;
                    curRefs = null;
                }
            }
        }

        // 生成批量 INSERT：一行一条 way，geom 用 WKT 文本由 PostGIS 隐式转 geometry
        StringBuilder batch = new StringBuilder();
        int inBatch = 0, emitted = 0;
        for (int i = 0; i < wayIds.size(); i++) {
            if (inBatch == 0) {
                batch.setLength(0);
                batch.append("INSERT INTO road_edge (osm_way_id, name, highway_type, road_level, geom) VALUES ");
            } else {
                batch.append(',');
            }
            batch.append('(').append(wayIds.get(i)).append(",'").append(wayNames.get(i)).append("','")
                    .append(wayTypes.get(i)).append("',").append(ROAD_LEVEL.get(wayTypes.get(i)))
                    .append(",'LINESTRING(").append(toWktCoords(wayRefs.get(i), nodes)).append(")')");
            if (++inBatch == BATCH_SIZE) {
                System.out.println(batch.append(';'));
                emitted += inBatch;
                inBatch = 0;
            }
        }
        if (inBatch > 0) {
            System.out.println(batch.append(';'));
            emitted += inBatch;
        }
        System.err.printf(Locale.ROOT, "解析完成：导入 %d 条道路边，跳过 %d 条（白名单外/顶点缺失）%n", emitted, skippedWay);
    }

    /** 把 way 的 nd 引用序列拼成 WKT 坐标串 "lon lat,lon lat,..."（WKT 是 x,y = 经度,纬度） */
    private static String toWktCoords(List<Long> refs, Map<Long, double[]> nodes) {
        StringBuilder sb = new StringBuilder(refs.size() * 24);
        for (int i = 0; i < refs.size(); i++) {
            double[] p = nodes.get(refs.get(i));
            if (i > 0) {
                sb.append(',');
            }
            // %.7f ≈ 1cm 精度，覆盖 OSM 原始数据 1e-7 度量级；Locale.ROOT 防小数点被本地化成逗号
            sb.append(String.format(Locale.ROOT, "%.7f %.7f", p[1], p[0]));
        }
        return sb.toString();
    }
}
