package com.glucose.common.enums;

import lombok.Getter;

/**
 * measure_type:1 空腹、2 餐前、3 餐后2小时、4 睡前、5 随机、6 凌晨;CGM 为 null。
 */
@Getter
public enum MeasureType {

    FASTING(1, "空腹"),
    PRE_MEAL(2, "餐前"),
    POST_MEAL_2H(3, "餐后2小时"),
    BEFORE_SLEEP(4, "睡前"),
    RANDOM(5, "随机"),
    EARLY_MORNING(6, "凌晨");

    private final int code;
    private final String label;

    MeasureType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public static boolean isValid(Integer code) {
        if (code == null) {
            return false;
        }
        for (MeasureType t : values()) {
            if (t.code == code) {
                return true;
            }
        }
        return false;
    }

    public static String labelOf(Integer code) {
        if (code == null) {
            return "";
        }
        for (MeasureType t : values()) {
            if (t.code == code) {
                return t.label;
            }
        }
        return "";
    }
}
