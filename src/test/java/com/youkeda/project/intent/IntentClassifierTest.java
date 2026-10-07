package com.youkeda.project.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class IntentClassifierTest {

    @Test
    void classifiesKnownIntentExamples() {
        IntentClassifier classifier = new IntentClassifier(null);

        assertEquals(IntentType.TEXT_REPLY, classifier.classify("你好").intent());
        assertEquals(IntentType.TEXT_REPLY, classifier.classify("今天上海天气怎么样").intent());
        assertEquals(IntentType.TEXT_REPLY, classifier.classify("帮我查一下明天的天气预报").intent());
        assertEquals(IntentType.TEXT_REPLY, classifier.classify("讲一个笑话").intent());
        assertEquals(IntentType.TEXT_REPLY, classifier.classify("介绍一下你自己").intent());

        assertEquals(IntentType.VOICE_REPLY, classifier.classify("请用语音回复我").intent());
        assertEquals(IntentType.VOICE_REPLY, classifier.classify("请用语音播报今天的天气").intent());
        assertEquals(IntentType.VOICE_REPLY, classifier.classify("发语音给我").intent());
        assertEquals(IntentType.VOICE_REPLY, classifier.classify("语音回答一下").intent());
        assertEquals(IntentType.VOICE_REPLY, classifier.classify("请朗读这段话").intent());
        assertEquals(IntentType.VOICE_REPLY, classifier.classify("speak the answer").intent());

        assertEquals(IntentType.IMAGE_GENERATION, classifier.classify("画一只猫").intent());
        assertEquals(IntentType.IMAGE_GENERATION, classifier.classify("生成一张森林图片").intent());
        assertEquals(IntentType.IMAGE_GENERATION, classifier.classify("帮我画个机器人").intent());
        assertEquals(IntentType.IMAGE_GENERATION, classifier.classify("draw a sunset").intent());
        assertEquals(IntentType.IMAGE_GENERATION, classifier.classify("create image of a dog").intent());
        assertEquals(IntentType.IMAGE_GENERATION, classifier.classify("生成图片：海边日落").intent());
        assertEquals(IntentType.IMAGE_GENERATION, classifier.classify("绘制一张海报").intent());
        assertEquals(IntentType.IMAGE_GENERATION, classifier.classify("生成一幅油画").intent());
    }

    @Test
    void accuracyIsAtLeast90Percent() {
        IntentClassifier classifier = new IntentClassifier(null);
        List<IntentCase> cases =
            List.of(
                new IntentCase("你好", IntentType.TEXT_REPLY),
                new IntentCase("今天天气怎么样", IntentType.TEXT_REPLY),
                new IntentCase("帮我查一下上海天气", IntentType.TEXT_REPLY),
                new IntentCase("你会什么", IntentType.TEXT_REPLY),
                new IntentCase("讲个笑话", IntentType.TEXT_REPLY),
                new IntentCase("介绍一下你自己", IntentType.TEXT_REPLY),
                new IntentCase("推荐一本小说", IntentType.TEXT_REPLY),
                new IntentCase("怎么学习编程", IntentType.TEXT_REPLY),
                new IntentCase("把这段翻译成英文", IntentType.TEXT_REPLY),
                new IntentCase("谢谢", IntentType.TEXT_REPLY),
                new IntentCase("用语音回复我", IntentType.VOICE_REPLY),
                new IntentCase("请用语音播报天气", IntentType.VOICE_REPLY),
                new IntentCase("发语音给我", IntentType.VOICE_REPLY),
                new IntentCase("语音回答一下", IntentType.VOICE_REPLY),
                new IntentCase("请朗读这段话", IntentType.VOICE_REPLY),
                new IntentCase("读出来给我听", IntentType.VOICE_REPLY),
                new IntentCase("speak the answer", IntentType.VOICE_REPLY),
                new IntentCase("reply by voice", IntentType.VOICE_REPLY),
                new IntentCase("用语音说出来", IntentType.VOICE_REPLY),
                new IntentCase("语音输出今天的天气", IntentType.VOICE_REPLY),
                new IntentCase("画一只猫", IntentType.IMAGE_GENERATION),
                new IntentCase("生成一张森林图片", IntentType.IMAGE_GENERATION),
                new IntentCase("帮我画个机器人", IntentType.IMAGE_GENERATION),
                new IntentCase("请画一座雪山", IntentType.IMAGE_GENERATION),
                new IntentCase("给我画一只熊猫", IntentType.IMAGE_GENERATION),
                new IntentCase("画张城市夜景", IntentType.IMAGE_GENERATION),
                new IntentCase("draw a sunset", IntentType.IMAGE_GENERATION),
                new IntentCase("create image of a dog", IntentType.IMAGE_GENERATION),
                new IntentCase("生成图片：海边日落", IntentType.IMAGE_GENERATION),
                new IntentCase("画一个 3D 头像", IntentType.IMAGE_GENERATION),
                new IntentCase("生成一张图片并用语音介绍", IntentType.VOICE_REPLY),
                new IntentCase("用语音说这张图怎么画", IntentType.VOICE_REPLY),
                new IntentCase("帮我画一张图然后用语音讲", IntentType.VOICE_REPLY),
                new IntentCase("绘制一张海报", IntentType.IMAGE_GENERATION),
                new IntentCase("生成一幅油画", IntentType.IMAGE_GENERATION),
                new IntentCase("说给我听", IntentType.VOICE_REPLY));

        int correct = 0;
        for (IntentCase intentCase : cases) {
            IntentType actual = classifier.classify(intentCase.text()).intent();
            if (actual == intentCase.expected()) {
                correct++;
            }
        }

        double accuracy = correct / (double) cases.size();
        assertTrue(
            accuracy >= 0.9,
            "Expected intent accuracy >= 90%, actual=" + (accuracy * 100) + "%");
        assertEquals(cases.size(), correct);
    }

    @Test
    void extractsImagePrompt() {
        IntentClassifier classifier = new IntentClassifier(null);
        assertEquals("猫", classifier.extractImagePrompt("画一只猫"));
        assertEquals("海边日落", classifier.extractImagePrompt("生成图片：海边日落"));
        assertEquals("机器人", classifier.extractImagePrompt("帮我画个机器人"));
        assertEquals(null, classifier.extractImagePrompt("你好"));
    }

    private record IntentCase(String text, IntentType expected) {}
}
