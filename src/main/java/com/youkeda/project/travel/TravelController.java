package com.youkeda.project.travel;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旅行规划 Agent REST 入口。
 *
 * <ul>
 *   <li>{@code POST /api/travel/plan} — 一句话目标 → 完整出行手册</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/travel")
public class TravelController {

    private final TravelAgentService travelAgentService;

    public TravelController(TravelAgentService travelAgentService) {
        this.travelAgentService = travelAgentService;
    }

    @PostMapping("/plan")
    public TravelAgentService.TravelPlanResult plan(@RequestBody TravelPlanRequest request) {
        if (request == null || request.goal() == null || request.goal().isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }
        return travelAgentService.plan(request.goal());
    }

    /** 请求体：用户的一句话最终目标，如「做一份上海三日游完整出行方案」。 */
    public record TravelPlanRequest(String goal) {}
}
