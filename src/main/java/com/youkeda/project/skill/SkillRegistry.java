package com.youkeda.project.skill;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 技能注册表：收集所有 {@link Skill}，按注册顺序做关键词命中匹配。
 */
@Service
public class SkillRegistry {

    private final List<Skill> skills;

    public SkillRegistry(List<Skill> skills) {
        this.skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /** 按注册顺序查找第一个关键词命中的技能，未命中返回 null。 */
    public SkillMatch match(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase();
        for (Skill skill : skills) {
            for (String keyword : skill.keywords()) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                if (lower.contains(keyword.toLowerCase())) {
                    return new SkillMatch(skill, keyword);
                }
            }
        }
        return null;
    }

    public List<Skill> skills() {
        return skills;
    }

    /** 命中结果：技能 + 命中的关键词 */
    public record SkillMatch(Skill skill, String keyword) {}
}
