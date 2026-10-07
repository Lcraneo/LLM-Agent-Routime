package com.youkeda.project.travel;

import com.youkeda.project.skill.Skill;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 旅行规划 Skill 入口：关键词命中后路由到 {@link TravelAgentService} 自主规划链路。
 *
 * <p>这是 Skill 与自主 Agent 的协作范型：Skill 负责「意图命中 + 路由」（快、确定、零 token），
 * 真正的多步拆解与工具调用交由自主 Agent 完成。命中「出行方案/旅游攻略/三日游」等词即触发。
 */
@Component
public class TravelPlanSkill implements Skill {

    private final TravelAgentService travelAgentService;

    public TravelPlanSkill(TravelAgentService travelAgentService) {
        this.travelAgentService = travelAgentService;
    }

    @Override
    public String name() {
        return "travel_plan";
    }

    @Override
    public List<String> keywords() {
        return List.of("出行方案", "旅游攻略", "旅行规划", "三日游", "行程规划", "旅游方案", "出游方案");
    }

    @Override
    public String description() {
        return "命中旅行规划关键词后，触发自主规划型 Agent，自动拆解多步并产出完整出行手册。";
    }

    @Override
    public String execute(String userInput) {
        return travelAgentService.plan(userInput).handbook();
    }
}
