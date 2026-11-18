/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.kooo.evcam.alarm.telegram.TelegramCommand;

/**
 * 车主发来的一句话怎么读（{@link TelegramCommand}）：只认配好的那个会话，只认命令、四个方向和
 * {@code guard} 那一族。
 *
 * How to read a message the owner sends ({@link TelegramCommand}): only the configured chat counts, and
 * only the commands, the four directions, and the {@code guard} family are recognized.
 */
public class TelegramCommandTest {

    @Test
    public void picturesAreAskedForInFourWays() {
        for (String word : new String[] {"pic", "photo", "snap", "snapshot", "PIC", "Photo"}) {
            TelegramCommand command = TelegramCommand.parseText(word);
            assertEquals(word, TelegramCommand.Verb.PICTURE, command.verb);
            assertEquals(word, TelegramCommand.ANY_LANE, command.lane);
        }
    }

    @Test
    public void aSlashAndABotMentionAreIgnored() {
        assertEquals(TelegramCommand.Verb.PICTURE, TelegramCommand.parseText("/pic").verb);
        assertEquals(TelegramCommand.Verb.PICTURE,
                TelegramCommand.parseText("/pic@ZeekrSentry_bot").verb);
        assertEquals("带方向也要认", 2, TelegramCommand.parseText("/pic@ZeekrSentry_bot left").lane);
    }

    @Test
    public void aDirectionPicksThatLane() {
        assertEquals(0, TelegramCommand.parseText("pic front").lane);
        assertEquals(1, TelegramCommand.parseText("pic rear").lane);
        assertEquals(2, TelegramCommand.parseText("picture left").lane);
        assertEquals(3, TelegramCommand.parseText("PHOTO RIGHT").lane);
        assertEquals("不认识的词当没写", TelegramCommand.ANY_LANE,
                TelegramCommand.parseText("pic roof").lane);
    }

    @Test
    public void statusIsItsOwnVerbAndTakesNoPicture() {
        assertEquals(TelegramCommand.Verb.STATUS, TelegramCommand.parseText("status").verb);
        assertEquals(TelegramCommand.Verb.STATUS, TelegramCommand.parseText("/state").verb);
    }

    @Test
    public void everythingElseIsUnknown() {
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("").verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("   ").verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText(null).verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("hello").verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("\uD83D\uDE00").verb);
    }

    @Test
    public void onlyTheConfiguredChatIsTheOwner() {
        assertTrue(TelegramCommand.fromOwner("7761390174", "7761390174"));
        assertTrue("负数是群组会话号，也照样按字符串比", TelegramCommand.fromOwner("-1001234", "-1001234"));
        assertFalse(TelegramCommand.fromOwner("999", "7761390174"));
        assertFalse("没配好之前谁都不是主人", TelegramCommand.fromOwner("7761390174", ""));
        assertFalse(TelegramCommand.fromOwner(null, "7761390174"));
        assertFalse(TelegramCommand.fromOwner("7761390174", null));
    }

    private static TelegramCommand.GuardAction guard(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals(text, TelegramCommand.Verb.GUARD, command.verb);
        return command.guard;
    }

    private static void notAGuard(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals(text, TelegramCommand.Verb.UNKNOWN, command.verb);
        assertEquals(text, null, command.guard);
    }

    @Test
    public void everyGuardPhraseMeansOneAction() {
        assertEquals(TelegramCommand.GuardAction.REPORT, guard("guard"));
        assertEquals(TelegramCommand.GuardAction.ON, guard("guard on"));
        assertEquals(TelegramCommand.GuardAction.OFF, guard("guard off"));
        assertEquals(TelegramCommand.GuardAction.HOME_SET, guard("guard home"));
        assertEquals(TelegramCommand.GuardAction.HOME_CLEAR, guard("guard home clear"));
        assertEquals(TelegramCommand.GuardAction.AUTO_OFF, guard("guard auto off"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ASK, guard("guard auto ask"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ASK_THEN_ON, guard("guard auto then"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ON, guard("guard auto on"));
        assertEquals("guard then 是 guard auto then 的短写法",
                TelegramCommand.GuardAction.AUTO_ASK_THEN_ON, guard("guard then"));
    }

    @Test
    public void aSlashABotMentionCaseAndWhitespaceDoNotMatterForGuard() {
        assertEquals("群里会写成 /guard@BotName on",
                TelegramCommand.GuardAction.ON, guard("/guard@ZeekrSentry_bot on"));
        assertEquals(TelegramCommand.GuardAction.REPORT, guard("/GUARD"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ASK, guard("  /Guard   Auto   ASK  "));
        assertEquals(TelegramCommand.GuardAction.HOME_CLEAR, guard("guard HOME clear"));
    }

    @Test
    public void aWordAfterGuardThatMeansNothingIsUnknown() {
        // 不猜：认不出来的词不会撑到某个最近的意思上
        // No guessing: an unrecognized word does not snap to a nearby meaning
        notAGuard("guard nonsense");
        notAGuard("guard off now");
        notAGuard("guard home on");
        notAGuard("guard auto");
        notAGuard("guard auto maybe");
        notAGuard("guard home clear now");
    }

    @Test
    public void theOlderCommandsDoNotGrowAGuardAction() {
        assertEquals(null, TelegramCommand.parseText("pic").guard);
        assertEquals(null, TelegramCommand.parseText("pic left").guard);
        assertEquals(null, TelegramCommand.parseText("status").guard);
        assertEquals(null, TelegramCommand.parseText("hello").guard);
    }

    // ------------------------------------------------------------------ 灵敏度 / sensitivity

    @Test
    public void sensitivityReportsMovesAndTakesANumber() {
        assertEquals(TelegramCommand.SensitivityAction.REPORT, sensitivity("sens"));
        assertEquals(TelegramCommand.SensitivityAction.REPORT, sensitivity("/sensitivity"));
        assertEquals(TelegramCommand.SensitivityAction.UP, sensitivity("sens up"));
        assertEquals(TelegramCommand.SensitivityAction.UP, sensitivity("sens higher"));
        assertEquals(TelegramCommand.SensitivityAction.UP, sensitivity("sens +"));
        assertEquals(TelegramCommand.SensitivityAction.DOWN, sensitivity("sens down"));
        assertEquals(TelegramCommand.SensitivityAction.DOWN, sensitivity("SENS lower"));
        assertEquals(TelegramCommand.SensitivityAction.DOWN, sensitivity("sens -"));
        assertEquals(TelegramCommand.SensitivityAction.SET, sensitivity("sens 7"));
        assertEquals(7, level("sens 7"));
        assertEquals("set 那个词是可选的", 4, level("sens set 4"));
        assertEquals(TelegramCommand.SensitivityAction.SET, sensitivity("sens 1"));
        assertEquals(1, level("sens 1"));
        assertEquals("10 是最大的一档", 10, level("sens 10"));
    }

    @Test
    public void aSlashAndABotMentionDoNotMatterForSensitivity() {
        assertEquals(TelegramCommand.SensitivityAction.UP, sensitivity("/sens@ZeekrSentry_bot up"));
        assertEquals(TelegramCommand.SensitivityAction.REPORT, sensitivity("  /SENS  "));
    }

    @Test
    public void aNumberOutsideOneToTenIsNotGuessedAt() {
        // 不猜：11 不是「最近的 10」，0 也不是 1
        // No guessing: 11 is not "10, nearly", and 0 is not 1
        notASensitivity("sens 0");
        notASensitivity("sens 11");
        notASensitivity("sens 99");
        notASensitivity("sens -3");
        notASensitivity("sens ten");
        notASensitivity("sens up up");
        notASensitivity("sens set");
        notASensitivity("sens set 0");
        notASensitivity("sens set 11");
        notASensitivity("sens auto");
    }

    @Test
    public void onlySensitivityCommandsCarryASensitivityAction() {
        assertEquals(null, TelegramCommand.parseText("pic").sensitivity);
        assertEquals(null, TelegramCommand.parseText("status").sensitivity);
        assertEquals(null, TelegramCommand.parseText("guard on").sensitivity);
        assertEquals(null, TelegramCommand.parseText("hello").sensitivity);
        // 数字只对 sens 有意义：pic 后面跟个 7 仍然是「拍一张」，不是「第 7 档」。
        // A number only means something after sens: "pic 7" is still one picture, not a level.
        TelegramCommand pic = TelegramCommand.parseText("pic 7");
        assertEquals(TelegramCommand.Verb.PICTURE, pic.verb);
        assertEquals(null, pic.sensitivity);
        assertEquals(TelegramCommand.ANY_LANE, pic.lane);
    }

    // ------------------------------------------------------------------ 暂停 / 恢复警报

    @Test
    public void alertsParsesOffOnAndPastTenseWords() {
        assertEquals(TelegramCommand.AlertsState.REPORT, alerts("alerts"));
        assertEquals(TelegramCommand.AlertsState.REPORT, alerts("/alarm"));
        assertEquals(TelegramCommand.AlertsState.OFF, alerts("alerts off"));
        assertEquals(TelegramCommand.AlertsState.OFF, alerts("alerts pause"));
        assertEquals(TelegramCommand.AlertsState.OFF, alerts("alerts stop"));
        assertEquals(TelegramCommand.AlertsState.ON, alerts("alerts on"));
        assertEquals(TelegramCommand.AlertsState.ON, alerts("alerts resume"));
        assertEquals(TelegramCommand.AlertsState.ON, alerts("ALERTS start"));
    }

    @Test
    public void aSlashAndABotMentionDoNotMatterForAlerts() {
        assertEquals(TelegramCommand.AlertsState.OFF, alerts("/alerts@ZeekrSentry_bot off"));
        assertEquals(TelegramCommand.AlertsState.REPORT, alerts("  /Alerts  "));
    }

    @Test
    public void anAlertsWordThatMeansNothingIsUnknown() {
        // 不猜：「maybe」不是开也不是关，猜一个方向比不回更糟
        // No guessing: "maybe" is neither on nor off, and guessing a direction is worse than silence
        notAlerts("alerts maybe");
        notAlerts("alerts off now");
        notAlerts("alerts on please");
        notAlerts("alerts 1");
    }

    @Test
    public void onlyAlertsCommandsCarryAnAlertsState() {
        assertEquals(null, TelegramCommand.parseText("pic").alerts);
        assertEquals(null, TelegramCommand.parseText("status").alerts);
        assertEquals(null, TelegramCommand.parseText("sens up").alerts);
        assertEquals(null, TelegramCommand.parseText("hello").alerts);
    }

    private static TelegramCommand.AlertsState alerts(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals("这句该是警报开关命令: " + text, TelegramCommand.Verb.ALERTS, command.verb);
        return command.alerts;
    }

    private static void notAlerts(String text) {
        assertEquals("不该认成警报开关命令: " + text,
                TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText(text).verb);
    }

    private static TelegramCommand.SensitivityAction sensitivity(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals("这句该是灵敏度命令: " + text, TelegramCommand.Verb.SENSITIVITY, command.verb);
        return command.sensitivity;
    }

    private static int level(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals("这句该是灵敏度命令: " + text, TelegramCommand.Verb.SENSITIVITY, command.verb);
        return command.sensitivityLevel;
    }

    private static void notASensitivity(String text) {
        assertEquals("不该认成灵敏度命令: " + text,
                TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText(text).verb);
    }
}
