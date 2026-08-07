package com.dataagent.lab.service;

import com.dataagent.lab.domain.ClarificationOption;
import com.dataagent.lab.domain.ClarificationPrompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class UserIntentClarifier {
    public Optional<ClarificationPrompt> clarify(String input) {
        String normalized = input.toLowerCase(Locale.ROOT).replaceAll("[，。！？?\\s]", "");
        boolean mentionsOrders = normalized.contains("订单") || normalized.contains("order");
        boolean broadRequest = normalized.matches(".*(看看|分析|了解|查询|查一下|情况|概况|怎么样).*");
        boolean hasMetric = containsAny(normalized,
                "数量", "订单数", "金额", "销售额", "平均", "最高", "最低", "状态", "字段", "表结构", "元数据");

        if (!mentionsOrders || !broadRequest || hasMetric) {
            return Optional.empty();
        }

        return Optional.of(new ClarificationPrompt(
                "你想从哪个角度查看订单？先确认指标可以避免 Agent 自行猜测口径。",
                List.of(
                        new ClarificationOption("已完成订单数量", "统计已完成订单数量"),
                        new ClarificationOption("各城市已完成订单金额", "统计各城市已完成订单金额"),
                        new ClarificationOption("已完成订单平均金额", "统计已完成订单平均金额"),
                        new ClarificationOption("按状态统计订单数", "按状态统计订单数")
                )
        ));
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
