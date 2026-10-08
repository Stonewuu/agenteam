package com.stonewu.agenteam.service.data;

import com.stonewu.agenteam.model.data.entity.DataField;
import com.stonewu.agenteam.service.http.ApiException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 字段名称逐字匹配来源，排序位置必须唯一且连续，不能带入未声明字段。
 */
@Component
public class DataFieldValidation {
    private static final Set<String> TYPES = Set.of("string", "integer", "decimal", "boolean", "date", "datetime",
        "object");

    public List<DataField> fields(List<DataField> fields) {
        if (fields == null || fields.isEmpty() || fields.size() > 100) {
            throw ApiException.invalidField("fields", "请选择一至一百个字段。");
        }
        var names = new HashSet<String>();
        var ordinals = new HashSet<Integer>();
        for (var field : fields) {
            if (field == null || field.name() == null || field.name().isBlank() || field.name()
                .codePointCount(0, field.name().length()) > 128
                || field.name().codePoints().anyMatch(Character::isISOControl) || !names.add(field.name())) {
                throw ApiException.invalidField("fields", "字段名不能为空、重复或包含控制字符。");
            }
            if (field.label() == null || field.label().isBlank() || field.label()
                .codePointCount(0, field.label().length()) > 80
                || field.label().codePoints()
                .anyMatch(Character::isISOControl) || field.valueType() == null || !TYPES.contains(field.valueType())) {
                throw ApiException.invalidField("fields", "请填写有效的字段名称与类型。");
            }
            if (field.ordinal() < 0 || field.ordinal() >= fields.size() || !ordinals.add(field.ordinal())) {
                throw ApiException.invalidField("fields", "字段顺序应从零开始连续排列，不能重复。");
            }
            if (field.sortable() && field.valueType().equals("object")) {
                throw ApiException.invalidField("fields", "JSON 对象字段不能用于排序。");
            }
        }
        return fields.stream().sorted(Comparator.comparingInt(DataField::ordinal)).toList();
    }
}
