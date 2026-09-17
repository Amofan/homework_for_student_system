package com.homework.analysis.shared.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.util.function.Consumer;

/**
 * 插入单行并取回自增主键。
 *
 * <p>不要用"插入后再查一次"来认领主键——无论回查的是 {@code max(id)} 还是业务字段，
 * 前提都是"查到的就是自己刚插入的那一行"，而并发下这个前提并不成立：
 * <ul>
 *   <li>{@code max(id)} 是普通一致性读，同一教师并发提交两次插入时，先提交的那一行
 *       会被后一个事务读到，两个事务于是拿到同一个主键，后插入的明细挂到别人的主表行上；</li>
 *   <li>按业务字段回查看似安全（唯一约束保证只有一行），但正确性依赖"业务键上恰好有
 *       唯一约束"这个隐式前提，约束一旦调整，回查就会静默取到别人的行。</li>
 * </ul>
 * 由驱动直接返回本次插入生成的主键没有这些问题。
 */
public final class GeneratedKeys {

    /** 只声明主键一列：用 {@code RETURN_GENERATED_KEYS} 时 H2 会把整行都当成生成键返回。 */
    private static final String[] ID_COLUMN = {"id"};

    private GeneratedKeys() {
    }

    /** 位置参数版，供持有 {@link JdbcTemplate} 的服务使用。 */
    public static long insert(JdbcTemplate jdbc, String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, ID_COLUMN);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keys);
        return require(keys);
    }

    /**
     * 具名参数版，供只注入了 {@link JdbcClient} 的服务使用，省得再注入一个 {@code JdbcTemplate}。
     * 绑定方式与原语句一致，直接传入原来的 {@code .param(...)} 链即可。
     */
    public static long insert(JdbcClient jdbc, String sql, Consumer<JdbcClient.StatementSpec> binder) {
        KeyHolder keys = new GeneratedKeyHolder();
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        binder.accept(statement);
        statement.update(keys, ID_COLUMN);
        return require(keys);
    }

    private static long require(KeyHolder keys) {
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("插入未返回自增主键");
        }
        return key.longValue();
    }

    /**
     * 条件插入版：允许“这次没有插入任何行”。
     *
     * <p>用于 {@code insert ... select ... where not exists (...)} 这种带前置条件的插入。
     * 零行插入不是异常，而是预期结果——例如候选登录名已被占用，调用方应当换一个候选值重试。
     * 返回 {@code null} 表示本次没有插入。
     */
    public static Long insertOrNull(JdbcClient jdbc, String sql, Consumer<JdbcClient.StatementSpec> binder) {
        KeyHolder keys = new GeneratedKeyHolder();
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        binder.accept(statement);
        statement.update(keys, ID_COLUMN);
        Number key = keys.getKey();
        return key == null ? null : key.longValue();
    }
}
