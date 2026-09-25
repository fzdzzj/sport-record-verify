package com.sportverify.record.config;

import com.sportverify.record.service.SubmitTxTiming;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 带物理连接获取计时的连接池（TASK-140 提交归因诊断，测量默认关闭）。
 *
 * <p>ShardingSphere 按 sharding.yaml 的 {@code dataSourceClassName} 反射建池；本类是
 * 普通 {@link DataSource} 包装，内部持有真实 {@link HikariDataSource}（懒初始化，首个
 * {@code getConnection} 才启动，与 HikariCP 自身语义一致）。{@link #getConnection()}
 * 的耗时即真实物理连接获取（含 HikariCP 池内等待）——与 Spring 代理层
 * {@code getConnection()}（ShardingSphereConnection 的廉价包装）不是同一层。</p>
 *
 * <p>为什么不是继承 HikariDataSource（TASK-140 实测复现）：ShardingSphere 5.4.1
 * {@code YamlDataSourceConfigurationSwapper.getProperties} 仅在类名字面量等于
 * {@code com.zaxxer.hikari.HikariDataSource} 时才从属性表剥离 {@code dataSourceClassName}
 * 键；其他类名会把该键当自定义属性绑进 HikariConfig，HikariCP 5.0.1 建池时优先走
 * 「dataSourceClassName 委托」分支（先于 jdbcUrl 分支），对委托实例套用 MySQL 默认
 * 连接属性 {@code netTimeoutForStreamingResults}（HikariCP 5.x 已移除该访问器）直接抛
 * RuntimeException。本类没有 {@code setDataSourceClassName}，该键被 ShardingSphere
 * 静默跳过，从结构上规避该分支；{@code dataSourceProperties}（MySQL 默认查询属性，
 * 由 ShardingSphere 反射并入）原样传给内层池，与直接使用 HikariDataSource 的驱动
 * 参数路径一致。</p>
 *
 * <p>归因边界：仅当当前线程已被 {@link SubmitTxTiming#armConnWait} 武装（计时开启
 * 的提交请求）才记录 connWait 段；未武装线程（状态回调、点赞等其他端点与后台任务）
 * 不产生样本。计时边界 = 进入 getConnection 到返回物理连接（获取失败也记到异常抛出
 * 为止，样本随该请求回滚丢弃）；{@code getConnection(username, password)} 变体与
 * 本工程调用路径未使用的属性同样代理，计时口径相同。SQL/JDBC 执行与提交刷盘仍无法
 * 在本位置分离，见 TASK-140 报告。</p>
 */
public class TimingHikariDataSource implements DataSource {

    private String jdbcUrl;

    private String username;

    private String password;

    private int maximumPoolSize = 10;

    private int minimumIdle = -1;

    private long connectionTimeout = 30000;

    private long idleTimeout = 600000;

    private long maxLifetime = 1800000;

    private long keepaliveTime;

    private long initializationFailTimeout = 1;

    /** MySQL 默认查询属性（ShardingSphere 反射并入；转发给驱动，口径同直接使用 Hikari） */
    private final Properties dataSourceProperties = new Properties();

    /** 内层真实 Hikari 池：懒初始化，失败不留半初始化实例 */
    private volatile HikariDataSource pool;

    @Override
    public Connection getConnection() throws SQLException {
        if (!SubmitTxTiming.connWaitArmed()) {
            return innerPool().getConnection();
        }
        long t0 = System.nanoTime();
        try {
            return innerPool().getConnection();
        } finally {
            SubmitTxTiming.recordConnWait(System.nanoTime() - t0);
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        if (!SubmitTxTiming.connWaitArmed()) {
            return innerPool().getConnection(username, password);
        }
        long t0 = System.nanoTime();
        try {
            return innerPool().getConnection(username, password);
        } finally {
            SubmitTxTiming.recordConnWait(System.nanoTime() - t0);
        }
    }

    /** 关闭内层池（ShardingSphere 池销毁路径按 close 约定调用） */
    @SuppressWarnings("PMD.UndefineMagicConstantRule")
    public void close() {
        HikariDataSource inner = pool;
        if (inner != null) {
            inner.close();
        }
    }

    private HikariDataSource innerPool() {
        HikariDataSource inner = pool;
        if (inner == null) {
            synchronized (this) {
                if (pool == null) {
                    pool = buildPool();
                }
                inner = pool;
            }
        }
        return inner;
    }

    private HikariDataSource buildPool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(minimumIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
        config.setKeepaliveTime(keepaliveTime);
        config.setInitializationFailTimeout(initializationFailTimeout);
        if (!dataSourceProperties.isEmpty()) {
            config.setDataSourceProperties(dataSourceProperties);
        }
        return new HikariDataSource(config);
    }

    // ==================== ShardingSphere / Hikari 属性绑定（setter 名与 HikariConfig 对齐） ====================

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** ShardingSphere 会从实例 getter 反推 username/password 归一化（StorageUnit），getter 必须与 setter 成对 */
    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public void setMinimumIdle(int minimumIdle) {
        this.minimumIdle = minimumIdle;
    }

    public void setConnectionTimeout(long connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public void setIdleTimeout(long idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public void setMaxLifetime(long maxLifetime) {
        this.maxLifetime = maxLifetime;
    }

    public void setKeepaliveTime(long keepaliveTime) {
        this.keepaliveTime = keepaliveTime;
    }

    public void setInitializationFailTimeout(long initializationFailTimeout) {
        this.initializationFailTimeout = initializationFailTimeout;
    }

    public Properties getDataSourceProperties() {
        return dataSourceProperties;
    }

    public void setDataSourceProperties(Properties dataSourceProperties) {
        if (dataSourceProperties != this.dataSourceProperties) {
            this.dataSourceProperties.clear();
            this.dataSourceProperties.putAll(dataSourceProperties);
        }
    }

    // ==================== DataSource 其余约定方法（本工程不使用，最小实现） ====================

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // 本工程无驱动日志 Writer 需求，忽略
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // 不支持登录超时，忽略（与不配置时的默认 0 一致）
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("getParentLogger is not supported");
    }
}
