package com.kooo.evcam.playback;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日期分组模型
 * 将同一天的视频/图片组聚合在一起
 * @param <T> 分组类型，目前是 PhotoGroup
 */
public class DateSection<T> {
    
    /** 日期字符串，格式为 yyyy-MM-dd */
    private final String dateString;
    
    /** 日期对象 */
    private final Date date;
    
    /** 该日期下的所有组 */
    private final List<T> items;
    
    /** 是否展开 */
    private boolean expanded;
    
    public DateSection(String dateString, Date date) {
        this.dateString = dateString;
        this.date = date;
        this.items = new ArrayList<>();
        this.expanded = isToday(date); // 只有今天默认展开
    }
    
    /**
     * 判断指定日期是否是今天
     */
    private boolean isToday(Date date) {
        Calendar today = Calendar.getInstance();
        Calendar targetDate = Calendar.getInstance();
        targetDate.setTime(date);
        
        return today.get(Calendar.YEAR) == targetDate.get(Calendar.YEAR)
                && today.get(Calendar.DAY_OF_YEAR) == targetDate.get(Calendar.DAY_OF_YEAR);
    }
    
    /**
     * 添加一个组到此日期
     */
    public void addItem(T item) {
        items.add(item);
    }
    
    /**
     * 获取日期字符串
     */
    public String getDateString() {
        return dateString;
    }
    
    /**
     * 获取日期对象
     */
    public Date getDate() {
        return date;
    }
    
    /**
     * 获取该日期下的所有组
     */
    public List<T> getItems() {
        return items;
    }
    
    /**
     * 获取组数量
     */
    public int getItemCount() {
        return items.size();
    }
    
    /**
     * 是否展开
     */
    public boolean isExpanded() {
        return expanded;
    }
    
    /**
     * 设置展开状态
     */
    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }
    
    /**
     * 切换展开/收起状态
     */
    public void toggleExpanded() {
        this.expanded = !this.expanded;
    }
    
    /**
     * 获取格式化的日期显示字符串
     * 今天显示"今天"，昨天显示"昨天"，其他显示日期
     */
    public String getFormattedDateDisplay(android.content.Context context) {
        Calendar today = Calendar.getInstance();
        Calendar targetDate = Calendar.getInstance();
        targetDate.setTime(date);
        
        // 清除时间部分，只比较日期
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        
        targetDate.set(Calendar.HOUR_OF_DAY, 0);
        targetDate.set(Calendar.MINUTE, 0);
        targetDate.set(Calendar.SECOND, 0);
        targetDate.set(Calendar.MILLISECOND, 0);
        
        long diffInDays = (today.getTimeInMillis() - targetDate.getTimeInMillis()) / (24 * 60 * 60 * 1000);
        
        if (diffInDays == 0) {
            return context.getString(com.kooo.evcam.R.string.date_today);
        } else if (diffInDays == 1) {
            return context.getString(com.kooo.evcam.R.string.date_yesterday);
        } else if (diffInDays == 2) {
            return context.getString(com.kooo.evcam.R.string.date_before_yesterday);
        }
        // 日期本身不写死格式：让系统按当前语言拼一个 —— 中文得到「8月28日」，
        // 英文得到「Aug 28」。原来写死 "MM月dd日" + Locale.CHINESE，
        // 英文界面下会冒出一段中文日期。
        Locale locale = Locale.getDefault();
        String skeleton = today.get(Calendar.YEAR) == targetDate.get(Calendar.YEAR)
                ? "MMMd" : "yMMMd";
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton);
        return new SimpleDateFormat(pattern, locale).format(date);
    }
    
    /**
     * 获取星期几
     */
    public String getDayOfWeek() {
        return new SimpleDateFormat("EEEE", Locale.getDefault()).format(date);
    }
    
    /**
     * 获取带星期的完整日期显示
     */
    public String getFullDateDisplay(android.content.Context context) {
        // 「今天 · 星期四」和「8月28日 星期四」的分隔符不一样：前者是两个并列的说法，
        // 后者是同一个日期的两部分。判据用相对天数，不去比对翻译过的文字 ——
        // 比字符串的话，换一种语言就全都对不上了。
        return getFormattedDateDisplay(context)
                + (isWithinLastThreeDays() ? " · " : " ")
                + getDayOfWeek();
    }

    /** 是不是今天 / 昨天 / 前天。 */
    private boolean isWithinLastThreeDays() {
        Calendar today = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.setTime(date);
        for (Calendar c : new Calendar[]{today, target}) {
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
        }
        long days = (today.getTimeInMillis() - target.getTimeInMillis()) / (24 * 60 * 60 * 1000);
        return days >= 0 && days <= 2;
    }
}
