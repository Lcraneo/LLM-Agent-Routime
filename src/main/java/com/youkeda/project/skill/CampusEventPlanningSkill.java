package com.youkeda.project.skill;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CampusEventPlanningSkill implements Skill {

    @Override
    public String name() {
        return "campus_event_planning";
    }

    @Override
    public List<String> keywords() {
        return List.of("校园活动", "校园AI", "AI分享会", "分享会", "校园讲座", "活动策划");
    }

    @Override
    public String description() {
        return "校园活动策划：生成活动目标、流程、人员、预算、宣传和风险基线。";
    }

    @Override
    public String execute(String userInput) {
        return "活动策划基线：目标→受众→时间地点→流程→人员→预算→宣传→风险→复盘。当前任务："
            + (userInput == null || userInput.isBlank() ? "请提供活动描述" : userInput.trim());
    }
}
