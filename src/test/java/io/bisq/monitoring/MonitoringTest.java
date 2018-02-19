package io.bisq.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/*

 */
@Slf4j
public class MonitoringTest {

    @Test
    public void regexTest() {
        String regex = "\"btcTxFee\"\\s*:\\s*(\\d+),";
        // old json format
        assertTrue(testRegex("{ \"dataMap\": { \"dogeTxFee\": 5000000, \"dashTxFee\": 50, \"btcTxFee\": 380, \"ltcTxFee\": 500 }, \"bitcoinFeesTs\": 1513173628 }", regex));
        // new json format
        assertTrue(testRegex("{ \"bitcoinFeesTs\" : 1519047027, \"dataMap\" : { \"dogeTxFee\" : 5000000, \"dashTxFee\" : 50, \"btcTxFee\" : 10, \"ltcTxFee\" : 500 } }", regex));
    }

    private boolean testRegex(String target, String regex) {
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(target);
        log.info(m.toString());
        if(m.find()) {
            log.info(m.group(1));
            return true;
        } else {
            log.error("Can't find txfee");
            return false;
        }
    }

}
