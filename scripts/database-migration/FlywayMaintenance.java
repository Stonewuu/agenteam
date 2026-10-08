import org.flywaydb.core.Flyway;

/**
 * 发布维护入口：归档旧迁移记录后显式建立版本 0 起点；不删除或重新初始化业务表。
 */
public final class FlywayMaintenance {
    private FlywayMaintenance() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("请指定 baseline、validate 或 migrate 操作");
        }
        String locations = System.getenv().getOrDefault("MIGRATION_LOCATIONS", "classpath:db/schema");
        Flyway flyway = Flyway.configure()
            .dataSource(required("MIGRATION_DB_URL"), required("MIGRATION_DB_USER"), required("MIGRATION_DB_PASSWORD"))
            .locations(locations.split(","))
            .baselineVersion("0")
            .baselineDescription("合并初始化脚本，保留现有业务数据")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .outOfOrder(false)
            .load();
        switch (args[0]) {
            case "baseline" -> {
                if (!"baseline-0".equals(required("MIGRATION_CONFIRM"))) {
                    throw new IllegalArgumentException("尚未确认版本 0 起点");
                }
                flyway.baseline();
                flyway.validate();
            }
            case "validate" -> flyway.validate();
            case "migrate" -> flyway.migrate();
            default -> throw new IllegalArgumentException("不支持的迁移维护操作");
        }
        for (var migration : flyway.info().all()) {
            System.out.println(migration.getVersion() + " " + migration.getType() + " " + migration.getState());
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少维护环境变量：" + name);
        }
        return value;
    }
}
