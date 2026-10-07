package com.youkeda.project.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JokeSkillTest {

    private final JokeSkill jokeSkill = new JokeSkill();

    @Test
    void executesAndReturnsNonBlankJoke() {
        String reply = jokeSkill.execute("讲个笑话");
        assertNotNull(reply);
        assertFalse(reply.isBlank());
        assertTrue(jokeSkill.keywords().contains("笑话"));
    }

    @Test
    void registryMatchesJokeKeywordAndMissesIrrelevant() {
        SkillRegistry registry = new SkillRegistry(List.of(jokeSkill));
        SkillRegistry.SkillMatch hit = registry.match("帮我讲个笑话呗");
        assertNotNull(hit);
        assertEquals("joke", hit.skill().name());
        // 关键词按注册顺序匹配，“讲个笑话”排在“笑话”前面，先命中
        assertEquals("讲个笑话", hit.keyword());

        assertNull(registry.match("今天天气真好"));
        assertNull(registry.match(""));
    }
}
