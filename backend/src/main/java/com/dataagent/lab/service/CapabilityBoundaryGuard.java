package com.dataagent.lab.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class CapabilityBoundaryGuard {
    private static final Pattern NON_DQL_SQL = Pattern.compile(
            "(?is)\\b(create|alter|drop|truncate|rename|comment|insert|replace|update|delete|merge|"
                    + "grant|revoke|commit|rollback|savepoint|release\\s+savepoint|"
                    + "start\\s+transaction|set\\s+transaction)\\b"
    );
    private static final Pattern CREATE_TABLE_REQUEST = Pattern.compile(
            "(?i)(新增|新建|创建|添加|建)\\s*(一张|一个)?\\s*"
                    + "[\\p{IsHan}a-z0-9_]{0,24}(数据)?表(?:并|且|，|。|：|:|\\s|$)"
    );
    private static final Pattern CHANGE_TABLE_REQUEST = Pattern.compile(
            "(修改|变更|调整|删除|删掉|移除|重命名|清空)\\s*(一张|一个)?\\s*"
                    + "[\\p{IsHan}a-z0-9_]{0,24}(数据)?表(结构|字段|列|索引|名称)?"
    );
    private static final Pattern CHANGE_COLUMN_REQUEST = Pattern.compile(
            "(给|为|在).{0,24}(表).{0,10}(新增|添加|删除|修改|重命名).{0,6}(字段|列|索引)"
    );
    private static final Pattern WRITE_DATA_REQUEST = Pattern.compile(
            "(向|往).{0,24}(表|数据库).{0,10}(插入|写入|新增|添加|更新|修改|删除|清空).{0,8}(数据|记录|行)"
    );
    private static final Pattern MUTATE_RECORD_REQUEST = Pattern.compile(
            "(新增|插入|写入|更新|修改|删除|清空|导入)\\s*"
                    + "(一条|一批|这些|指定)\\s*(订单|用户|商品|数据|记录|行)"
    );
    private static final Pattern ASSIGN_RECORD_REQUEST = Pattern.compile(
            "(把|将).{0,32}(更新为|改为|修改为|写入|插入|删除|清空)"
    );
    private static final Pattern PERMISSION_REQUEST = Pattern.compile(
            "(授权|赋权|增加权限|回收权限|撤销权限|删除权限)|"
                    + "(授予|回收|撤销|删除).{0,32}权限"
    );
    private static final Pattern TRANSACTION_REQUEST = Pattern.compile(
            "(提交|回滚|开启|开始|结束|保存)\\s*(当前)?\\s*(数据库)?事务|保存点"
    );

    public Optional<String> notImplementedReason(String input) {
        String normalized = input == null ? "" : input.trim();
        if (NON_DQL_SQL.matcher(normalized).find()
                || CREATE_TABLE_REQUEST.matcher(normalized).find()
                || CHANGE_TABLE_REQUEST.matcher(normalized).find()
                || CHANGE_COLUMN_REQUEST.matcher(normalized).find()
                || WRITE_DATA_REQUEST.matcher(normalized).find()
                || MUTATE_RECORD_REQUEST.matcher(normalized).find()
                || ASSIGN_RECORD_REQUEST.matcher(normalized).find()
                || PERMISSION_REQUEST.matcher(normalized).find()
                || TRANSACTION_REQUEST.matcher(normalized).find()) {
            return Optional.of(
                    "当前版本只支持 DQL：数据目录检索、表结构查看和单条只读 SELECT 查询。"
                            + "DDL、DML、DCL 和 TCL 均标记为待完成，不会交给模型规划或数据库执行。"
                            + "开放这些能力前需要补齐权限控制、变更审批、影响评估、审计和回滚方案。"
            );
        }
        return Optional.empty();
    }
}
