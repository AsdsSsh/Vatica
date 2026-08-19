package com.example.vatica.skill;

/** 20A 只接受严格 x.y.z，避免版本排序依赖字符串或引入另一套依赖解析器。 */
record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {

    static SemanticVersion parse(String value) {
        String[] parts = value.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("操作失败：Skill 版本必须使用 x.y.z。");
        }
        try {
            SemanticVersion version = new SemanticVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
            if (version.major < 0 || version.minor < 0 || version.patch < 0) {
                throw new NumberFormatException("negative version");
            }
            return version;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("操作失败：Skill 版本必须使用 x.y.z。", e);
        }
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int value = Integer.compare(major, other.major);
        if (value == 0) value = Integer.compare(minor, other.minor);
        if (value == 0) value = Integer.compare(patch, other.patch);
        return value;
    }
}
