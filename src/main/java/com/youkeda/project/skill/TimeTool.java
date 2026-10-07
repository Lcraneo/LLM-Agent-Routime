package com.youkeda.project.skill;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;


@Component
public class TimeTool implements Skill {

    @Override
    public String name() {
        return "time";
    }

    @Override
    public List<String> keywords() {
        return List.of("现在几点", "现在时间", "几点了", "今天几号", "当前时间", "几号了");
    }

    @Override
    public String description() {
        return "返回当前服务器时间，例如：现在几点、今天几号";
    }

    @Override
    public String execute(String userInput) {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss");
        return "当前时间：" + now.format(fmt);
    }
}
