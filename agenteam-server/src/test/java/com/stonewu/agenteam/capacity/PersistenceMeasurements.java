package com.stonewu.agenteam.capacity;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * 观察 JDBC 已成功执行的写语句和提交，单独统计包含写语句的事务，不记录正文或参数。
 */
public final class PersistenceMeasurements {
    private static final Pattern WRITE = Pattern.compile("(?is)^\\s*(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+`?([a-zA-Z0-9_]+)`?");
    private volatile Window current;

    public static final class Window {
        private final long started = System.nanoTime();
        private final ConcurrentLinkedQueue<Write> writes = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Long> commits = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Long> rollbacks = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<WriteError> failedWrites = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Long> autoCommitBatches = new ConcurrentLinkedQueue<>();

        public Map<String, Object> report(long ended) {
            double seconds = (ended - started) / 1_000_000_000.0;
            var committed = commits.stream().filter(time -> time <= ended).sorted().toList();
            var statements = writes.stream().filter(write -> write.at() <= ended).toList();
            Map<String, Long> tables = new LinkedHashMap<>();
            statements.forEach(write -> tables.merge(write.table(), 1L, Long::sum));
            Map<String, Long> operations = new LinkedHashMap<>();
            statements.forEach(write -> operations.merge(write.table() + "." + write.operation(), 1L, Long::sum));
            int peak = 0;
            for (int left = 0, right = 0; right < committed.size(); right++) {
                while (committed.get(right) - committed.get(left) >= 1_000_000_000L) {
                    left++;
                }
                peak = Math.max(peak, right - left + 1);
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("seconds", seconds);
            result.put("writeStatements", statements.size());
            result.put("writeTransactions", committed.size());
            result.put("writeTps", committed.size() / seconds);
            result.put("peakWriteTps", peak);
            result.put("rollbacksWithWrites", rollbacks.stream().filter(time -> time <= ended).count());
            result.put("failedWriteStatements", failedWrites.stream().filter(error -> error.at() <= ended).count());
            result.put("deadlocks", failedWrites.stream().filter(error -> error.at() <= ended && error.code() == 1213).count());
            result.put("multiStatementAutoCommitBatches", autoCommitBatches.stream().filter(time -> time <= ended).count());
            result.put("tables", tables);
            result.put("operations", operations);
            return result;
        }
    }

    private record Write(long at, String table, String operation) {
    }

    private record WriteError(long at, int code) {
    }

    public Window start() {
        var window = new Window();
        current = window;
        return window;
    }

    public void stop() {
        current = null;
    }

    private Connection connection(Connection target) {
        var transaction = new Pending();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                boolean autoCommit = method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0]);
                Object value = invoke(target, method, args);
                if (method.getName().equals("commit") || autoCommit) {
                    transaction.commit();
                } else if (method.getName().equals("rollback") && (args == null || args.length == 0)) {
                    transaction.rollback();
                } else if (value instanceof Statement statement && (method.getName().equals("prepareStatement")
                    || method.getName().equals("createStatement"))) {
                    String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
                    return statement(statement, target, transaction, sql);
                }
                return value;
            });
    }

    private final class Pending {
        private Window window;

        private void executed(String sql, Connection connection) throws SQLException {
            var active = current;
            if (active == null || sql == null) {
                return;
            }
            var matcher = WRITE.matcher(sql);
            if (!matcher.find()) {
                return;
            }
            active.writes.add(new Write(System.nanoTime(), matcher.group(2), matcher.group(1).split("\\s+")[0].toLowerCase()));
            window = active;
            if (connection.getAutoCommit()) {
                commit();
            }
        }

        private void commit() {
            if (window != null) {
                window.commits.add(System.nanoTime());
                window = null;
            }
        }

        private void rollback() {
            if (window != null) {
                window.rollbacks.add(System.nanoTime());
                window = null;
            }
        }
    }

    private Object statement(Statement target, Connection connection, Pending transaction, String preparedSql) {
        Class<?> type = target instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        var batch = new ArrayList<String>();
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : preparedSql;
            var active = current;
            boolean batchExecution = method.getName().equals("executeBatch") || method.getName().equals("executeLargeBatch");
            if (active != null && batchExecution && batch.size() > 1 && connection.getAutoCommit()) {
                active.autoCommitBatches.add(System.nanoTime());
            }
            Object result;
            try {
                result = invoke(target, method, args);
            } catch (SQLException failed) {
                if (active != null && sql != null && WRITE.matcher(sql).find() && method.getName().startsWith("execute")) {
                    active.failedWrites.add(new WriteError(System.nanoTime(), failed.getErrorCode()));
                }
                throw failed;
            }
            switch (method.getName()) {
                case "addBatch" -> batch.add(sql);
                case "clearBatch" -> batch.clear();
                case "executeBatch", "executeLargeBatch" -> {
                    for (String command : batch) {
                        transaction.executed(command, connection);
                    }
                    batch.clear();
                }
                case "execute", "executeUpdate", "executeLargeUpdate" -> transaction.executed(sql, connection);
                default -> {
                }
            }
            return result;
        });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean
        static PersistenceMeasurements persistenceMeasurements() {
            return new PersistenceMeasurements();
        }

        @Bean
        static BeanPostProcessor persistenceConnections(PersistenceMeasurements measurements) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource source) || !name.equals("dataSource")) {
                        return bean;
                    }
                    return new DelegatingDataSource(source) {
                        @Override
                        public Connection getConnection() throws SQLException {
                            return measurements.connection(super.getConnection());
                        }

                        @Override
                        public Connection getConnection(String user, String password) throws SQLException {
                            return measurements.connection(super.getConnection(user, password));
                        }
                    };
                }
            };
        }
    }
}
