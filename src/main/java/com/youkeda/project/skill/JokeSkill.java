package com.youkeda.project.skill;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 自定义技能：讲笑话。
 *
 * <p>纯本地执行，从内置笑话库随机抽取一条返回，无需调用大模型。
 * 触发关键词：讲个笑话 / 来个笑话 / 说个笑话 / 笑话 / joke 等。
 */
@Component
public class JokeSkill implements Skill {

    private static final List<String> JOKES = List.of(
        "为什么程序员喜欢用暗色主题？因为光线会吸引 bug。",
        "调试代码就像当侦探：你是凶手，也是侦探。",
        "为什么数学书总是很沮丧？因为它有太多的问题。",
        "为什么程序员总分不清万圣节和圣诞节？因为 Oct 31 == Dec 25。",
        "我家路由器对我说：你连不上的不是 WiFi，是人生。",
        "一根字符串走进酒吧，bartender 问：要不要切片？它说：不了，我已经是 split 的了。"
    );

    @Override
    public String name() {
        return "joke";
    }

    @Override
    public List<String> keywords() {
        return List.of("讲个笑话", "讲笑话", "来个笑话", "说个笑话", "讲个", "笑话", "joke");
    }

    @Override
    public String description() {
        return "讲一个随机笑话，纯本地执行，不调用大模型。";
    }

    @Override
    public String execute(String userInput) {
        return JOKES.get(ThreadLocalRandom.current().nextInt(JOKES.size()));
    }
}
