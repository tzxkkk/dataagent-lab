package com.dataagent.lab.planner;

import com.dataagent.lab.domain.AgentPlan;
import com.dataagent.lab.domain.PlannerUsage;
import com.dataagent.lab.domain.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OfflinePlanner implements AgentPlanner {
    private static final PlannerDescriptor DESCRIPTOR = new PlannerDescriptor(
            "offline", "offline-rules-v2", "deterministic", true
    );
    private static final Pattern MONTH_PATTERN = Pattern.compile(
            "\\b(20\\d{2}-(?:0[1-9]|1[0-2]))\\b"
    );

    @Override
    public AgentPlan plan(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);

        if (containsAny(normalized, "分表", "物理表", "月份路由", "路由到", "partition")) {
            String datasetId = datasetId(normalized);
            List<String> months = months(normalized);
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("datasetId", datasetId);
            if (!months.isEmpty()) {
                arguments.put("startMonth", months.get(0));
                arguments.put("endMonth", months.size() == 1 ? months.get(0) : months.get(1));
            }
            return plan("将逻辑数据集解析为已维护的物理表",
                    "resolve_dataset_tables", arguments);
        }

        if (containsAny(normalized, "逻辑数据集", "主题域", "logical dataset", "datasets")) {
            return plan("搜索面向业务的逻辑数据目录",
                    "search_datasets", Map.of("query", datasetQuery(normalized, input)));
        }

        if (containsAny(normalized, "表结构", "结构", "字段", "schema", "column")) {
            String tableName = containsAny(normalized, "用户", "dim_user", "user")
                    ? "dim_user" : "fact_order";
            return plan("查看指定数据表的结构", "get_table_schema",
                    Map.of("tableName", tableName));
        }

        if (containsAny(normalized, "最高", "top")
                && containsAny(normalized, "城市", "city")
                && containsAny(normalized, "金额", "amount", "gmv")) {
            String sql = "SELECT u.city, SUM(o.order_amount) AS total_amount "
                    + "FROM fact_order o JOIN dim_user u ON u.user_id = o.user_id "
                    + "WHERE o.status = 'COMPLETED' GROUP BY u.city "
                    + "ORDER BY total_amount DESC LIMIT 1";
            return sqlPlan("查询已完成订单金额最高的城市", sql);
        }

        if (containsAny(normalized, "武汉", "wuhan")
                && containsAny(normalized, "金额", "amount", "gmv")) {
            String sql = "SELECT SUM(o.order_amount) AS total_amount "
                    + "FROM fact_order o JOIN dim_user u ON u.user_id = o.user_id "
                    + "WHERE o.status = 'COMPLETED' AND u.city = '武汉'";
            return sqlPlan("汇总武汉的已完成订单金额", sql);
        }

        if (containsAny(normalized, "城市", "city")
                && containsAny(normalized, "完成订单数", "已完成订单数", "completed orders per city")) {
            String sql = "SELECT u.city, COUNT(*) AS completed_count "
                    + "FROM fact_order o JOIN dim_user u ON u.user_id = o.user_id "
                    + "WHERE o.status = 'COMPLETED' GROUP BY u.city ORDER BY u.city";
            return sqlPlan("按城市统计已完成订单数量", sql);
        }

        if (containsAny(normalized, "城市", "city")
                && containsAny(normalized, "用户数", "用户数量", "users per city")) {
            String sql = "SELECT city, COUNT(*) AS user_count FROM dim_user GROUP BY city ORDER BY city";
            return sqlPlan("按城市统计用户数量", sql);
        }

        if (containsAny(normalized, "城市", "city")
                && containsAny(normalized, "金额", "销售额", "amount", "gmv")) {
            String sql = "SELECT u.city, SUM(o.order_amount) AS total_amount "
                    + "FROM fact_order o JOIN dim_user u ON u.user_id = o.user_id "
                    + "WHERE o.status = 'COMPLETED' GROUP BY u.city ORDER BY total_amount DESC";
            return sqlPlan("按城市汇总已完成订单金额", sql);
        }

        if (containsAny(normalized, "按状态", "各状态", "by status")) {
            String sql = "SELECT status, COUNT(*) AS order_count "
                    + "FROM fact_order GROUP BY status ORDER BY status";
            return sqlPlan("按状态统计订单数量", sql);
        }

        if (normalized.contains("101") && containsAny(normalized, "状态", "status")) {
            String sql = "SELECT order_id, status FROM fact_order WHERE order_id = 101";
            return sqlPlan("查询订单 101 的状态", sql);
        }

        if (containsAny(normalized, "大于100", ">100", "above 100", "greater than 100")) {
            String sql = "SELECT COUNT(*) AS high_value_count FROM fact_order "
                    + "WHERE status = 'COMPLETED' AND order_amount > 100";
            return sqlPlan("统计高金额已完成订单数量", sql);
        }

        if (containsAny(normalized, "待处理", "pending")
                && containsAny(normalized, "金额", "amount")) {
            String sql = "SELECT SUM(order_amount) AS pending_amount "
                    + "FROM fact_order WHERE status = 'PENDING'";
            return sqlPlan("汇总待处理订单金额", sql);
        }

        if (containsAny(normalized, "最高金额", "最大金额", "maximum", "highest amount")) {
            String sql = "SELECT MAX(order_amount) AS maximum_amount "
                    + "FROM fact_order WHERE status = 'COMPLETED'";
            return sqlPlan("查询已完成订单的最高金额", sql);
        }

        if (containsAny(normalized, "最低金额", "最小金额", "minimum", "lowest amount")) {
            String sql = "SELECT MIN(order_amount) AS minimum_amount "
                    + "FROM fact_order WHERE status = 'COMPLETED'";
            return sqlPlan("查询已完成订单的最低金额", sql);
        }

        if (containsAny(normalized, "平均", "均值", "average")) {
            String sql = "SELECT AVG(order_amount) AS average_amount "
                    + "FROM fact_order WHERE status = 'COMPLETED'";
            return sqlPlan("计算已完成订单的平均金额", sql);
        }

        if (containsAny(normalized, "总金额", "金额合计", "total amount")
                && containsAny(normalized, "完成", "completed")) {
            String sql = "SELECT SUM(order_amount) AS total_amount "
                    + "FROM fact_order WHERE status = 'COMPLETED'";
            return sqlPlan("汇总已完成订单总金额", sql);
        }

        if (containsAny(normalized, "待处理", "pending")
                && containsAny(normalized, "数量", "订单数", "count")) {
            String sql = "SELECT COUNT(*) AS pending_count FROM fact_order WHERE status = 'PENDING'";
            return sqlPlan("统计待处理订单数量", sql);
        }

        if (containsAny(normalized, "已取消", "取消", "cancelled")
                && containsAny(normalized, "数量", "订单数", "count")) {
            String sql = "SELECT COUNT(*) AS cancelled_count FROM fact_order WHERE status = 'CANCELLED'";
            return sqlPlan("统计已取消订单数量", sql);
        }

        if (containsAny(normalized, "全部订单", "订单总数", "all orders")) {
            String sql = "SELECT COUNT(*) AS order_count FROM fact_order";
            return sqlPlan("统计全部订单数量", sql);
        }

        if (containsAny(normalized, "完成订单", "已完成订单", "completed")) {
            String sql = "SELECT COUNT(*) AS completed_count FROM fact_order WHERE status = 'COMPLETED'";
            return sqlPlan("统计已完成订单数量", sql);
        }

        String query;
        if (containsAny(normalized, "订单", "order")) {
            query = "订单";
        } else if (containsAny(normalized, "用户", "user")) {
            query = "用户";
        } else if (containsAny(normalized, "城市", "city")) {
            query = "城市";
        } else {
            query = input;
        }
        return plan("在操作数据前搜索元数据", "search_metadata", Map.of("query", query));
    }

    private AgentPlan sqlPlan(String rationale, String sql) {
        return plan(rationale, "run_readonly_sql", Map.of("sql", sql));
    }

    private AgentPlan plan(String rationale, String toolName, Map<String, Object> arguments) {
        return new AgentPlan(
                rationale,
                List.of(new ToolInvocation(toolName, arguments)),
                new PlannerUsage(DESCRIPTOR.promptVersion(), DESCRIPTOR.model(), 0, 0)
        );
    }

    @Override
    public PlannerDescriptor descriptor() {
        return DESCRIPTOR;
    }

    private boolean containsAny(String input, String... candidates) {
        for (String candidate : candidates) {
            if (input.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String datasetId(String input) {
        if (containsAny(input, "支付", "payment")) {
            return "payments";
        }
        if (containsAny(input, "退款", "refund")) {
            return "refunds";
        }
        if (containsAny(input, "库存", "inventory")) {
            return "inventory";
        }
        return "orders";
    }

    private String datasetQuery(String normalized, String original) {
        if (containsAny(normalized, "交易", "transaction")) {
            return "交易";
        }
        if (containsAny(normalized, "供应链", "supply chain")) {
            return "供应链";
        }
        if (containsAny(normalized, "支付", "payment")) {
            return "支付";
        }
        if (containsAny(normalized, "退款", "refund")) {
            return "退款";
        }
        if (containsAny(normalized, "订单", "order")) {
            return "订单";
        }
        return original;
    }

    private List<String> months(String input) {
        List<String> months = new ArrayList<>();
        Matcher matcher = MONTH_PATTERN.matcher(input);
        while (matcher.find() && months.size() < 2) {
            months.add(matcher.group(1));
        }
        return months;
    }
}
