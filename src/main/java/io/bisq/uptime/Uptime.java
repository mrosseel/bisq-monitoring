package io.bisq.uptime;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import lombok.extern.slf4j.Slf4j;
import net.gpedro.integrations.slack.SlackApi;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/*

 */
@Slf4j
public class Uptime {
    private Runtime rt = Runtime.getRuntime();
    private static SlackApi api;

    public static boolean ENABLE_SLACK = false;

    public static String PRICE_NODE_VERSION = "0.6.0";
    public static int LOOP_SLEEP_SECONDS = 10 * 60;
    public static int REPORTING_INTERVAL_SECONDS = 3600;
    public static int REPORTING_NR_LOOPS = REPORTING_INTERVAL_SECONDS / LOOP_SLEEP_SECONDS;

    public static int PROCESS_TIMEOUT_SECONDS = 60;

    public static List<String> clearnetBitcoinNodes = Arrays.asList(
            "138.68.117.247",
            "78.47.61.83",
            "174.138.35.229",
            "80.233.134.60",
            "192.41.136.217",
            "5.189.166.193",
            "37.221.198.57"
    );

    public static List<String> onionBitcoinNodes = Arrays.asList(
            "mxdtrjhe2yfsx3pg.onion",
            "poyvpdt762gllauu.onion",
            "r3dsojfhwcm7x7p6.onion",
            "vlf5i3grro3wux24.onion",
            "3r44ddzjitznyahw.onion",
            "i3a5xtzfm4xwtybd.onion",
            "7sl6havdhtgefwo2.onion"
    );

    public static List<String> onionPriceNodes = Arrays.asList(
            "ceaanhbvluug4we6.onion",
            "rb2l2qale2pqzjyo.onion"
    );

    public static List<String> seedNodes = Arrays.asList(
        //    "5quyxpxheyvzmb2d.onion:8000", // @mrosseel
         //   "ef5qnzx6znifo3df.onion:8000", // @alexej996
         //   "s67qglwhkgkyvr74.onion:8000", // @emzy
            "jhgcy2won7xnslrb.onion:8000", // @sqrrm
            "jhgcy2won7xnslr.onion:8000" // @sqrrm ERROR
    );

    Set<NodeInfo> errorNodes = new HashSet<>();
    HashMap<String, String> errorNodeMap = new HashMap<>();

    public void checkClearnetBitcoinNodes(List<String> ipAddresses) {
        checkBitcoinNode(ipAddresses, false);
    }

    public void checkOnionBitcoinNodes(List<String> onionAddresses) {
        checkBitcoinNode(onionAddresses, true);
    }

    public void checkPriceNodes(List<String> ipAddresses, boolean overTor) {
        NodeType nodeType = NodeType.PRICE_NODE;
        for (String address : ipAddresses) {
            BitcoinNodeResult result = new BitcoinNodeResult();
            result.setAddress(address);

            ProcessResult getFeesResult = executeProcess((overTor ? "torify " : "") + "curl " + address + (overTor ? "" : "8080") + "/getFees", PROCESS_TIMEOUT_SECONDS);
            if (getFeesResult.getError() != null) {
                handleError(nodeType, address, getFeesResult.getError());
                continue;
            }
            boolean correct = getFeesResult.getResult().contains("btcTxFee");
            if (!correct) {
                handleError(nodeType, address, "Result does not contain expected keyword: " + getFeesResult.getResult());
                continue;
            }

            ProcessResult getVersionResult = executeProcess((overTor ? "torify " : "") + "curl " + address + (overTor ? "" : "8080") + "/getVersion", PROCESS_TIMEOUT_SECONDS);
            if (getVersionResult.getError() != null) {
                handleError(nodeType, address, getVersionResult.getError());
                continue;
            }
            correct = PRICE_NODE_VERSION.equals(getVersionResult.getResult());
            if (!correct) {
                handleError(nodeType, address, "Incorrect version:" + getVersionResult.getResult());
                continue;
            }

            markAsGoodNode(nodeType, address);
        }
    }

    /** NOTE: does not work on MAC netcat version */
    public void checkSeedNodes(List<String> addresses) {
        NodeType nodeType = NodeType.SEED_NODE;
        for (String address : addresses) {
            ProcessResult getFeesResult = executeProcess("./src/main/shell/seednodes.sh " + address, PROCESS_TIMEOUT_SECONDS);
            if (getFeesResult.getError() != null) {
                handleError(nodeType, address, getFeesResult.getError());
                continue;
            }
            markAsGoodNode(nodeType, address);
        }
    }

    private void checkBitcoinNode(List<String> ipAddresses, boolean overTor) {
        NodeType nodeType = NodeType.BITCOIN_NODE;
        for (String address : ipAddresses) {
            BitcoinNodeResult result = new BitcoinNodeResult();
            result.setAddress(address);

            try {
                Process pr = rt.exec("pipenv run " + (overTor ? "torify " : "") + "python ./src/main/python/protocol.py " + address);
                boolean noTimeout = pr.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!noTimeout) {
                    handleError(nodeType, address, "Timeout");
                    continue;
                }
                String resultString = convertStreamToString(pr.getInputStream());
                log.info(resultString.toString());
                String[] splitResult = resultString.split(",");
                if (splitResult.length != 4) {
                    handleError(nodeType, address, "Incorrect result length:" + resultString);
                    continue;
                }
                result.setVersion(splitResult[1]);
                result.setHeight(Long.parseLong(splitResult[2]));
                result.setServices(Integer.parseInt(splitResult[3].trim()));
            } catch (IOException e) {
                handleError(nodeType, address, e.getMessage());
                continue;
            } catch (InterruptedException e) {
                handleError(nodeType, address, e.getMessage());
                continue;
            }
            markAsGoodNode(nodeType, address);
        }
    }

    private ProcessResult executeProcess(String command, int timeoutSeconds) {
        Process pr = null;
        boolean noTimeout = false;
        try {
            pr = rt.exec(command);
            noTimeout = pr.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (IOException e) {
            return new ProcessResult("", e.getMessage());
        } catch (InterruptedException e) {
            return new ProcessResult("", e.getMessage());
        }
        if (!noTimeout) {
            return new ProcessResult(null, "Timeout");
        }
        return new ProcessResult(convertStreamToString(pr.getInputStream()), null);
    }

    static String convertStreamToString(java.io.InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    public void handleError(NodeType nodeType, String address, String reason) {
        log.error("Error in {} {}, reason: {}", nodeType.toString(), address, reason);
        if (!isAlreadyBadNode(address, nodeType, reason)) {
            SlackTool.send(api, "Error: " + nodeType.getPrettyName() + " " + address, appendBadNodesSizeToString(reason));
        }
    }

    private void markAsGoodNode(NodeType nodeType, String address) {
        Optional<NodeInfo> any = findNodeInfoByAddress(address);
        if (any.isPresent()) {
            boolean removed = errorNodes.remove(any.get());
            SlackTool.send(api, "Fixed: " + nodeType.getPrettyName() + " " + address, appendBadNodesSizeToString("No longer in error"));
        }
    }

    private Optional<NodeInfo> findNodeInfoByAddress(String address) {
        return errorNodes.stream().filter(nodeInfo -> nodeInfo.getAddress().equals(address)).findAny();
    }

    private boolean isAlreadyBadNode(String address, NodeType nodeType, String reason) {
        Optional<NodeInfo> any = findNodeInfoByAddress(address);
        if(any.isPresent()) {
            return any.get().getErrorReason().add((reason.isEmpty())?"Empty reason":reason);
        }
        return !errorNodes.add(new NodeInfo(address, nodeType, Arrays.asList(reason)));
    }

    private String appendBadNodesSizeToString(String body) {
        return body + " (now " + errorNodes.size() + " node(s) have errors, next check in +/-" + Math.round(LOOP_SLEEP_SECONDS/60) + " minutes)";
    }

    public String printErrorNodes() {
        return errorNodes.stream().sorted().map(nodeInfo -> nodeInfo.getNodeType().toString() + "\t|\t" + nodeInfo.getAddress() + " : " + nodeInfo.getReasonListAsString()).collect(Collectors.joining("\n"));
    }

    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();
        final String USE_SLACK = "useSlack";
        parser.accepts(USE_SLACK, "Output is posted to a slack channel")
                .withRequiredArg();
        final String SLACK_SECRET = "slackSecret";
        parser.accepts(SLACK_SECRET, "The slack secret URL")
                .withRequiredArg();

        OptionSet options;
        try {
            options = parser.parse(args);
        } catch (OptionException ex) {
            System.out.println("error: " + ex.getMessage());
            System.out.println();
            parser.printHelpOn(System.out);
            System.exit(-1);
            return;
        }

        ENABLE_SLACK = options.has(USE_SLACK) ? (boolean) options.valueOf(USE_SLACK) : false;
        log.info("Slack usage is set to {}", ENABLE_SLACK);
        if(ENABLE_SLACK && options.has(SLACK_SECRET)) {
            String secret = (String) options.valueOf(SLACK_SECRET);
            log.info("Using slack secret: {}", secret);
            api = new SlackApi(secret);
        }

        Uptime uptime = new Uptime();

        log.info("Startup. All nodes in error will be shown fully in this first run.");
        SlackTool.send(api, NodeType.MONITORING_NODE.getPrettyName(), "Startup. All nodes in error will be shown fully in this first run.");
        int counter = 0;
        boolean isReportingLoop;
        while (true) {
            try {
                log.info("Starting checks...");
                uptime.checkPriceNodes(onionPriceNodes, true);
                uptime.checkClearnetBitcoinNodes(clearnetBitcoinNodes);
                uptime.checkOnionBitcoinNodes(onionBitcoinNodes);
                //uptime.checkSeedNodes(seedNodes);
                log.info("Stopping checks, now sleeping for {} seconds.", LOOP_SLEEP_SECONDS);

                // prepare reporting
                isReportingLoop = (counter % REPORTING_NR_LOOPS == 0);
                if (isReportingLoop) {
                    String errorNodeOutputString = uptime.printErrorNodes();
                    if (!errorNodeOutputString.isEmpty()) {
                        log.info("Nodes in error: \n{}", errorNodeOutputString);
                        SlackTool.send(api, NodeType.MONITORING_NODE.getPrettyName(), "Nodes in error: \n" + errorNodeOutputString);
                    } else {
                        log.info("No errors");
                        SlackTool.send(api, NodeType.MONITORING_NODE.getPrettyName(), "No errors");
                    }
                }

                try {
                    Thread.sleep(1000 * LOOP_SLEEP_SECONDS);
                } catch (InterruptedException e) {
                    log.error("Error during sleep", e);
                }
                counter++;
            } catch (Throwable e) {
                log.error("Could not send message to slack", e);
            }
        }
    }
}
