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
 * 为止，样本随该请求回滚丢弃）；{@code getConnection(username, password)} 变体同样
 * 代理并按相同口径计时。SQL/JDBC 执行与提交刷盘仍无法在本位置
 * 分离，见 TASK-140 报告。</p>
 *
 * <p>关闭路径：本类实现 {@link AutoCloseable}——ShardingSphere 5.4.1 池销毁
 * （{@code DataSourcePoolDestroyer}）仅按 {@code instanceof AutoCloseable} 判别并调用
 * {@code close()}，普通 DataSource 包装若不声明该接口则内层 Hikari 池在优雅关闭时
 * 不会被关闭（TASK-140 收口后修订）。关闭语义对齐 {@code HikariDataSource}：
 * {@code close()} 无论内层池是否已创建都标记关闭状态，之后 {@code getConnection()}
 * 抛 {@link SQLException} 且不得经懒初始化重建池（否则优雅关闭后连接池会被重新打开，
 * 收口后修订二补判别式）。close 与首次懒建池在同一 {@code synchronized(this)} 边界协调
 * （TASK-141）：首次建池在临界区内复查关闭状态，close 在同一临界区内读取内层池引用——
 * 「已通过入口检查但尚未建池」时发生 close，首次取连接在建池区被拒绝，不会留下由本
 * 包装类新建且无人关闭的内层池；该锁只覆盖建池构造与 close 的引用读取，稳态取连接与
 * 内层池 {@code getConnection}（最长 30s 的池内等待）不持本类锁。YAML 属性实际转发（已核对，测试锁定）：
 * {@code jdbcUrl/username/password/maximumPoolSize/connectionTimeout/
 * initializationFailTimeout} 由包装类 setter 转发至内层池配置；{@code idleTimeout/
 * maxLifetime/minimumIdle/keepaliveTime} 由 ShardingSphere 池元数据默认值注入后同样
 * 转发；{@code driverClassName} 被 ShardingSphere 反射跳过（驱动由 URL 推断，与直接
 * 使用 HikariDataSource 一致）；{@code dataSourceClassName} 键无对应 setter 被静默
 * 跳过。</p>
 */
public class TimingHikariDataSource implements DataSource, AutoCloseable {

    private String jdbcUrl;

    private String username;

    private String password;

    private int maximumPoolSize = 10;

    /** 与 ShardingSphere 池元数据默认一致（生产必注入；-1 会被 HikariCP 5.x setter 拒绝） */
    private int minimumIdle = 1;

    private long connectionTimeout = 30000;

    private long idleTimeout = 600000;

    private long maxLifetime = 1800000;

    private long keepaliveTime;

    private long initializationFailTimeout = 1;

    /** MySQL 默认查询属性（ShardingSphere 反射并入；转发给驱动，口径同直接使用 Hikari） */
    private final Properties dataSourceProperties = new Properties();

    /** 内层真实 Hikari 池：懒初始化，失败不留半初始化实例 */
    private volatile HikariDataSource pool;

    /** 关闭状态：无论内层池是否已创建都标记（对齐 HikariDataSource 语义） */
    private volatile boolean closed;

    @Override
    public Connection getConnection() throws SQLException {
        ensureOpen();
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
        ensureOpen();
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

    /** 关闭后拒绝取连接并禁止懒重建：否则优雅关闭后的请求会把连接池重新打开 */
    private void ensureOpen() throws SQLException {
        if (closed) {
            throw new SQLException("TimingHikariDataSource has been closed.");
        }
    }

    /** 关闭状态（对齐 HikariDataSource：未建池时 close 同样标记） */
    public boolean isClosed() {
        return closed;
    }

    /** 关闭：先标记再关内层池；未建池时仅标记（此后取连接拒绝、不得懒重建）。
     * 内层池引用必须在与首次建池相同的 {@code synchronized(this)} 边界内读取——否则
     * 「已通过入口检查、尚未建池」的取连接会与 close 交错建出无人关闭的内层池（TASK-141）；
     * 内层池关闭在锁外执行，不持包装类锁等待数据库资源。 */
    public void close() {
        closed = true;
        HikariDataSource inner;
        synchronized (this) {
            inner = pool;
        }
        if (inner != null) {
            inner.close();
        }
    }

    /** 懒建池：fast path 不加锁；建池临界区内复查关闭状态——close 完成后进入该区必须拒绝，
     * 不得再建新池。锁只覆盖建池构造与 close 的引用读取，稳态取连接（内层池
     * {@code getConnection} 最长 30s 池内等待）不持本类锁（TASK-141）。 */
    private HikariDataSource innerPool() throws SQLException {
        HikariDataSource inner = pool;
        if (inner != null) {
            return inner;
        }
        synchronized (this) {
            if (closed) {
                throw new SQLException("TimingHikariDataSource has been closed.");
            }
            if (pool == null) {
                pool = buildPool();
            }
            return pool;
        }
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
