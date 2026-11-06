package com.eno.stockbot.config;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 記錄每支 SQL 的耗時：DEBUG 等級印出 SQL 與參數，超過 {@value #SLOW_SQL_MS} ms 以 WARN 記錄。
 * 做成 Spring Bean，MyBatis-Plus 會自動註冊。
 */
@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class}),
        @Signature(type = StatementHandler.class, method = "batch", args = {Statement.class})})
public class SqlTimingInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SqlTimingInterceptor.class);
    static final long SLOW_SQL_MS = 500;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms >= SLOW_SQL_MS || log.isDebugEnabled()) {
                StatementHandler handler = (StatementHandler) invocation.getTarget();
                BoundSql sql = handler.getBoundSql();
                List<Object> params = params(handler, sql);
                if (ms >= SLOW_SQL_MS) {
                    log.warn("慢查詢 {} ms: {} {}", ms, oneLine(sql.getSql()), params);
                } else {
                    log.debug("{} ms: {} {}", ms, oneLine(sql.getSql()), params);
                }
            }
        }
    }

    /** 依 ? 的順序取出參數值（Wrapper 的條件值也會展開），和 MyBatis 設定參數的方式相同。 */
    private static List<Object> params(StatementHandler handler, BoundSql sql) {
        Configuration configuration = (Configuration) SystemMetaObject.forObject(handler)
                .getValue("delegate.configuration");
        Object param = sql.getParameterObject();
        MetaObject meta = param == null ? null : configuration.newMetaObject(param);
        List<Object> values = new ArrayList<>();
        for (ParameterMapping m : sql.getParameterMappings()) {
            String name = m.getProperty();
            if (sql.hasAdditionalParameter(name)) {
                values.add(sql.getAdditionalParameter(name));
            } else if (param == null) {
                values.add(null);
            } else if (configuration.getTypeHandlerRegistry().hasTypeHandler(param.getClass())) {
                values.add(param);
            } else {
                values.add(meta.getValue(name));
            }
        }
        return values;
    }

    static String oneLine(String sql) {
        return sql.strip().replaceAll("\\s+", " ");
    }
}
