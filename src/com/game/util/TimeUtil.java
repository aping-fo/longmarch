package com.game.util;


import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.Calendar;
import java.util.Date;
import java.util.function.Supplier;

/**
 * 关于时间的工具类
 */
public class TimeUtil {

    public static String CUR_TIME_FORMAT = "";

    public static final long ONE_SECOND = 1000;

    public static final long ONE_MIN = 60 * ONE_SECOND;
    public static final long ONE_HOUR = 60 * ONE_MIN;
    public static final long HALF_HOUR = 30 * ONE_MIN;
    public static final long ONE_DAY = 24 * ONE_HOUR;

    //public static final SimpleDateFormat sdf = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" );

    public static final ThreadLocal<SimpleDateFormat> sdf = ThreadLocal.withInitial(new Supplier<SimpleDateFormat>() {
        @Override
        public SimpleDateFormat get() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        }
    });

    public static final String FORMAT_STR = "yyyy-MM-dd HH:mm:ss";
    public static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern(FORMAT_STR);

    /**
     * 获得两个时间相差的分钟数
     *
     * @param one
     * @param two
     * @return
     */
    public static int getDifferMin(Date one, Date two) {
        if (null == one || null == two) {
            return 0;
        }
        return (int) ((two.getTime() - one.getTime()) / (1000 * 60));
    }

    /**
     * 获得两个时间相差的秒数
     *
     * @param one
     * @param two
     * @return
     */
    public static int getDifferSec(Date one, Date two) {
        if (null == one || null == two) {
            return 0;
        }
        return (int) ((two.getTime() - one.getTime()) / (1000));
    }

    public static long getTimeNow() {
        return System.currentTimeMillis();
    }

    /**
     * 今天凌晨时间
     */
    public static long getTodayBeginTime() {

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    public static boolean isSameDate(long one, long two) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(one);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(two);

        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.MONTH) == b.get(Calendar.MONTH) && a.get(Calendar.DATE) == b.get(Calendar.DATE);
    }

    //计算时间消逝
    public static int timePassSec(long one, long two) {
        long pass = (one - two) / 1000l;
        return (int) pass;
    }

    public static Calendar parseDateTime(String str) {
        Calendar cal = Calendar.getInstance();
        try {
            cal.setTime(sdf.get().parse(str));
        } catch (ParseException e) {
        }
        return cal;
    }

    /**
     * 检测当前时间是否在给定的时间范围内
     *
     * @param timeArr
     * @return
     */
    public static boolean checkTimeIn(int[] timeArr) {
        Calendar c = Calendar.getInstance();
        int hour = c.get(Calendar.HOUR_OF_DAY);
        boolean ret = false;
        int len = timeArr.length;
        for (int i = 0; i < len; i += 2) {
            int openHour = timeArr[i];
            int endHour = timeArr[i + 1];
            if (openHour <= hour && endHour > hour) {
                ret = true;
                break;
            }
        }
        return ret;
    }

    public static long getNextDay() {
        LocalDateTime dateTime = LocalDateTime.now();
        long nextTime = dateTime.withHour(0).withMinute(0).withSecond(0).with(ChronoField.MILLI_OF_SECOND,0)
                .plusDays(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return nextTime;
    }

    public static void main(String[] args){
        getNextDay();
    }
}
