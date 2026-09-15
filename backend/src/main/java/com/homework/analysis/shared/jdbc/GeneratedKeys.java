package com.homework.analysis.shared.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;

/**
 * 插入单行并取回自增主键。
 *
 * <p>不要用"插入后再查一次 {@code select max(id)}"来拿主键：那是普通一致性读，
 * 读视图建立在语句执行时，同一教师并发提交两次插入时，先提交的那一行会被后一个
 * 事务读到，两个事务于是拿到同一个主键，后插入的明细会挂到别人的主表行上。
 * 由驱动直接返回本次插入生成的主键没有这个竞态。
 */
public final class GeneratedKeys {

    /** 只声明主键一列：用 {@code RETURN_GENERATED_KEYS} 时 H2 会把整行都当成生成键返回。 */
    private static final String[] ID_COLUMN = {"id"};

    private GeneratedKeys() {
    }

    public static long insert(JdbcTemplate jdbc, String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, ID_COLUMN);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("插入未返回自增主键");
        }
        return key.longValue();
    }
}
