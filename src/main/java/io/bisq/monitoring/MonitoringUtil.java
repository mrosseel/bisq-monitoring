package io.bisq.monitoring;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*

 */
public class MonitoringUtil {

    public static boolean isPatternFound(String target, String regex) {
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(target);
        return m.find();
    }
}
