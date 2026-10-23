package com.ohmytoken;

/** 状态栏总量的统计范围 */
enum DisplayRange {
    TODAY(1),
    WEEK(7),
    MONTH(30),
    ALL(-1);

    private final int days;

    DisplayRange(int days) {
        this.days = days;
    }

    String label() {
        return OhMyTokenBundle.message("range." + name());
    }

    int days() {
        return days;
    }

    /** 设置页下拉框的显示文本 */
    @Override
    public String toString() {
        return label();
    }

    static DisplayRange byName(String name) {
        for (DisplayRange r : values()) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return TODAY;
    }
}
