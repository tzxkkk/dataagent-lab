package com.dataagent.lab.planner;

import com.dataagent.lab.domain.AgentPlan;
import com.dataagent.lab.domain.PlannerUsage;
import com.dataagent.lab.domain.ToolInvocation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class OfflinePlanner implements AgentPlanner {
    private static final PlannerDescriptor DESCRIPTOR = new PlannerDescriptor(
            "offline", "offline-rules-v2", "deterministic", true
    );

    @Override
    public AgentPlan plan(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);

        if (containsAny(normalized, "表结构", "结构", "字段", "schema", "column")) {
            String tableName = containsAny(normalized, "用户", "dim_user", "user")
                    ? "dim_user" : "fact_order";
            return plan("Inspect the requested table schema", "get_table_schema",
                    Map.of("tableName", tableName));
        }

        if (containsAny(normalized, "最高", "top")
                && containsAny(normalized, "城市", "city")
                && containsAny(normalized, "金额", "amount", "gmv")) {
            String sql = "SELECT u.city, SUM(o.order_amount) AS total_amount "
                    + "FROM fact_order o JOIN dim_user u ON u.user_id = o.user_id "
                    + "WHERE o.status = 'COMPLETED' GROUP BY u.city "
                    + "ORDER BY total_amount DESC LIMIT 1";
            return sqlPlan("Find the city with the highest completed order amount", sql);
        }

        if (containsAny(normalized, "武汉", "wuhan")
                && containsAny(normalized, "金额", "amount", "gmv")) {
            String sql = "SELECT SUM(o.order_amount) AS total_amount "
                    + "FROM fact_order o JOIN dim_user u ON u.user_id = o.user_id "
                    + "WHERE o.status = 'COMPLETED' AND u.city = '武汉'";
            return sqlPlan("Aggregate completed order amount for Wuhan", sql);
        }

        if (containsAny(normalized, "城市", "city")
                && containsAny(normalized, "完成订单数", "已完成订单数", "completed orders per city")) {
            String sql = "SELECT u.city, COUNT(*) AS completed_count "
                    + "FROM fact_order o JOIN dim_user u ON u.user_id = o.user_id "
                    + "WHERE o.status = 'COMPLETED' GROUP BY u.city ORDER BY u.city";
            return sqlPlan("Count completed orders by city", sql);
        }

        if (containsAny(normalized, "城市", "city")
                && containsAny(normalized, "用户数", "用户数量", "users per city")) {
            String sql = "SELECT city, COUNT(*) AS user_count FROM dim_user GROUP BY city ORDER BY city";
            return sqlPlan("Count users by city", sql);
        }

        if (containsAny(normalized, "城市", "city")
                && containsAny(normalized, "金额", "销售额", "amount", "gmv")) {
            String sql = "SELECT u.city, SUM(o.order_amount) AS total_amount "
                    + "FROM fact_order o JOIN dim_user u ON u.user_id = o.user_id "
                    + "WHERE o.status = 'COMPLETED' GROUP BY u.city ORDER BY total_amount DESC";
            return sqlPlan("Aggregate completed order amount by city", sql);
        }

        if (containsAny(normalized, "按状态", "各状态", "by status")) {
            String sql = "SELECT status, COUNT(*) AS order_count "
                    + "FROM fact_order GROUP BY status ORDER BY status";
            return sqlPlan("Count orders by status", sql);
        }

        if (normalized.contains("101") && containsAny(normalized, "状态", "status")) {
            String sql = "SELECT order_id, status FROM fact_order WHERE order_id = 101";
            return sqlPlan("Find the status of order 101", sql);
        }

        if (containsAny(normalized, "大于100", ">100", "above 100", "greater than 100")) {
            String sql = "SELECT COUNT(*) AS high_value_count FROM fact_order "
                    + "WHERE status = 'COMPLETED' AND order_amount > 100";
            return sqlPlan("Count high-value completed orders", sql);
        }

        if (containsAny(normalized, "待处理", "pending")
                && containsAny(normalized, "金额", "amount")) {
            String sql = "SELECT SUM(order_amount) AS pending_amount "
                    + "FROM fact_order WHERE status = 'PENDING'";
            return sqlPlan("Aggregate pending order amount", sql);
        }

        if (containsAny(normalized, "最高金额", "最大金额", "maximum", "highest amount")) {
            String sql = "SELECT MAX(order_amount) AS maximum_amount "
                    + "FROM fact_order WHERE status = 'COMPLETED'";
            return sqlPlan("Find the maximum completed order amount", sql);
        }

        if (containsAny(normalized, "最低金额", "最小金额", "minimum", "lowest amount")) {
            String sql = "SELECT MIN(order_amount) AS minimum_amount "
                    + "FROM fact_order WHERE status = 'COMPLETED'";
            return sqlPlan("Find the minimum completed order amount", sql);
        }

        if (containsAny(normalized, "平均", "均值", "average")) {
            String sql = "SELECT AVG(order_amount) AS average_amount "
                    + "FROM fact_order WHERE status = 'COMPLETED'";
            return sqlPlan("Calculate the average completed order amount", sql);
        }

        if (containsAny(normalized, "总金额", "金额合计", "total amount")
                && containsAny(normalized, "完成", "completed")) {
            String sql = "SELECT SUM(order_amount) AS total_amount "
                    + "FROM fact_order WHERE status = 'COMPLETED'";
            return sqlPlan("Aggregate total completed order amount", sql);
        }

        if (containsAny(normalized, "待处理", "pending")
                && containsAny(normalized, "数量", "订单数", "count")) {
            String sql = "SELECT COUNT(*) AS pending_count FROM fact_order WHERE status = 'PENDING'";
            return sqlPlan("Count pending orders", sql);
        }

        if (containsAny(normalized, "已取消", "取消", "cancelled")
                && containsAny(normalized, "数量", "订单数", "count")) {
            String sql = "SELECT COUNT(*) AS cancelled_count FROM fact_order WHERE status = 'CANCELLED'";
            return sqlPlan("Count cancelled orders", sql);
        }

        if (containsAny(normalized, "全部订单", "订单总数", "all orders")) {
            String sql = "SELECT COUNT(*) AS order_count FROM fact_order";
            return sqlPlan("Count all orders", sql);
        }

        if (containsAny(normalized, "完成订单", "已完成订单", "completed")) {
            String sql = "SELECT COUNT(*) AS completed_count FROM fact_order WHERE status = 'COMPLETED'";
            return sqlPlan("Count completed orders", sql);
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
        return plan("Search metadata before operating on data", "search_metadata", Map.of("query", query));
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
}
