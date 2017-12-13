package io.bisq.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.fail;

/*

 */
@Slf4j
public class MonitoringTest {

    @Test
    public void regexTest() {
        String testSTring = "{ \"dataMap\": { \"dogeTxFee\": 5000000, \"dashTxFee\": 50, \"btcTxFee\": 380, \"ltcTxFee\": 500 }, \"bitcoinFeesTs\": 1513173628 }";
        //"btcTxFee": 310
        Pattern p = Pattern.compile("\"btcTxFee\":\\s*(\\d+),");
        Matcher m = p.matcher(testSTring);
        log.info(m.toString());
        if(m.find()) {
            log.info(m.group(1));
        } else {
           log.error("Can't find txfee");
           fail();
        }

    }

}
