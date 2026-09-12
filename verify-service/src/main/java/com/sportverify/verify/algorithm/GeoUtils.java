package com.sportverify.verify.algorithm;

/**
 * 地理计算工具（Haversine 公式）。
 *
 * <p>用于逐点瞬时速度（预处理）、R3 停留位移、R4 距离一致性计算；
 * 距离单位为米，经纬度单位为度。</p>
 */
public final class GeoUtils {

    /** 地球平均半径（米） */
    private static final double EARTH_RADIUS_M = 6_371_000;

    private GeoUtils() {
    }

    /**
     * 计算两经纬度点间球面距离（米）。
     */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double dLat = radLat2 - radLat1;
        double dLng = Math.toRadians(lng2) - Math.toRadians(lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }
}
