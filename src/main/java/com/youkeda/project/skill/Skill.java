package com.youkeda.project.skill;

import java.util.List;

/**
 * 技能（Skill）：通过关键词命中的确定性执行单元。
 *
 * <p>与 {@code com.youkeda.project.tool.AgentTool}（LLM function-calling 工具）不同，
 * Skill 不依赖大模型决策，命中关键词后直接本地执行，响应快、结果可控、零 token 成本。
 */
public interface Skill {

    /** 技能唯一名称 */
    String name();

    /** 触发关键词列表，任一命中即触发该技能（按注册顺序匹配） */
    List<String> keywords();

    /** 技能描述，用于对外展示 */
    String description();

    /** 命中后执行，返回回复内容（不调用 LLM） */
    String execute(String userInput);
}
