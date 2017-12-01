package io.bisq.monitoring;

import lombok.extern.slf4j.Slf4j;
import net.gpedro.integrations.slack.SlackApi;
import net.gpedro.integrations.slack.SlackMessage;

/**
 * Created by mike on 28/02/16.
 */
@Slf4j
public class SlackTool {

    public static void send(SlackApi api, String username, String body) {
        if(Monitoring.isSlackEnabled) {
            try {
                api.call(new SlackMessage(username, body));
            } catch (Throwable e) {
                log.error("Couldn't send slack message", e);
            }
        } else {
            log.info("Not logging due to debug");
        }
    }
}
