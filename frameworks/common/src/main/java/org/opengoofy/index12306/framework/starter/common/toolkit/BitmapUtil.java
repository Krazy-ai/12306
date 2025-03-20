package org.opengoofy.index12306.framework.starter.common.toolkit;

import java.text.SimpleDateFormat;
import java.util.Date;

public class BitmapUtil {
    /**
     * 计算时间对应的十分钟维度索引
     * @param date 时间
     * @return 十分钟维度索引
     */
    public static int getTenMinuteIndex(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("HHmm");
        String timeStr = sdf.format(date);
        int hours = Integer.parseInt(timeStr.substring(0, 2));
        int minutes = Integer.parseInt(timeStr.substring(2));
        return hours * 6 + minutes / 10;
    }

    /**
     * 构建 Redis Key
     * @param cardId 乘车人id
     * @param date 日期
     * @return Redis Key
     */
    public static String buildRedisKey(String cardId, Date date) {
        //TODO 身份证号码的安全性 需要优化
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        String dateStr = sdf.format(date);
        return cardId + "_" + dateStr;
    }
}
