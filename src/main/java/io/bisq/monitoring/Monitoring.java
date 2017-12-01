package io.bisq.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.internal.Strings;
import lombok.extern.slf4j.Slf4j;
import net.gpedro.integrations.slack.SlackApi;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static spark.Spark.get;
import static spark.Spark.port;

/*

 */
@Slf4j
public class Monitoring {
    private Runtime rt = Runtime.getRuntime();
    private static SlackApi priceApi;
    private static SlackApi seedApi;
    private static SlackApi btcApi;

    // CMD line arguments
    public static final String USE_SLACK = "useSlack";
    public static final String SLACK_PRICE_SECRET = "slackPriceSecret";
    public static final String SLACK_SEED_SECRET = "slackSeedSecret";
    public static final String SLACK_BTC_SECRET = "slackBTCSecret";
    public static final String LOCAL_YAML = "localYaml";

    // CMD line argument DATA
    public static boolean isSlackEnabled = false;
    public static String slackPriceSecretData = null;
    public static String slackSeedSecretData = null;
    public static String slackBTCSecretData = null;
    public static String localYamlData = null;


    public static String PRICE_NODE_VERSION = "0.6.0";
    public static int LOOP_SLEEP_SECONDS = 10 * 60;
    public static int REPORTING_INTERVAL_SECONDS = 3600;
    public static int REPORTING_NR_LOOPS = REPORTING_INTERVAL_SECONDS / LOOP_SLEEP_SECONDS;

    public static int PROCESS_TIMEOUT_SECONDS = 60;

    public static List<String> clearnetBitcoinNodes = Arrays.asList(
            "138.68.117.247",
            "178.62.34.210", // same as 138
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
            "i3a5xtzfm4xwtybd.onion"
    );

    public static List<String> onionPriceNodes = Arrays.asList(
            "ceaanhbvluug4we6.onion",
            "rb2l2qale2pqzjyo.onion",
            "xc3nh4juf2hshy7e.onion"  // STEPHAN
    );

    public static List<String> seedNodes = Arrays.asList(
            "5quyxpxheyvzmb2d.onion:8000", // @mrosseel
            "ef5qnzx6znifo3df.onion:8000", // @alexej996
            "s67qglwhkgkyvr74.onion:8000", // @emzy
            "jhgcy2won7xnslrb.onion:8000" // @sqrrm
    );

    Set<NodeDetail> allNodes = new HashSet<>();
    HashMap<String, String> errorNodeMap = new HashMap<>();
    LocalDateTime startTime = LocalDateTime.now();

    public Monitoring(String localYamlData) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        /*

        try {
            NodeConfig nodeConfig = mapper.readValue(new File(localYamlData), NodeConfig.class);
            System.out.println(ReflectionToStringBuilder.toString(nodeConfig, ToStringStyle.MULTI_LINE_STYLE));
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        FileOutputStream fos = null;
        try {
            NodeConfig nodeConfig = new NodeConfig();
            fos = new FileOutputStream("out.yaml");
            SequenceWriter sw = mapper.writerWithDefaultPrettyPrinter().writeValues(fos);
            sw.write(nodeConfig);

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        */

    }

    public void checkClearnetBitcoinNodes(SlackApi api, List<String> ipAddresses) {
        checkBitcoinNode(api, ipAddresses, false);
    }

    public void checkOnionBitcoinNodes(SlackApi api, List<String> onionAddresses) {
        checkBitcoinNode(api, onionAddresses, true);
    }

    public void checkPriceNodes(SlackApi api, List<String> ipAddresses, boolean overTor) {
        NodeType nodeType = NodeType.PRICE_NODE;
        for (String address : ipAddresses) {
            BitcoinNodeResult result = new BitcoinNodeResult();
            result.setAddress(address);

            ProcessResult getFeesResult = executeProcess((overTor ? "torify " : "") + "curl " + address + (overTor ? "" : "8080") + "/getFees", PROCESS_TIMEOUT_SECONDS);
            if (getFeesResult.getError() != null) {
                handleError(api, nodeType, address, getFeesResult.getError());
                continue;
            }
            boolean correct = getFeesResult.getResult().contains("btcTxFee");
            if (!correct) {
                handleError(api, nodeType, address, "Result does not contain expected keyword: " + getFeesResult.getResult());
                continue;
            }

            ProcessResult getVersionResult = executeProcess((overTor ? "torify " : "") + "curl " + address + (overTor ? "" : "8080") + "/getVersion", PROCESS_TIMEOUT_SECONDS);
            if (getVersionResult.getError() != null) {
                handleError(api, nodeType, address, getVersionResult.getError());
                continue;
            }
            correct = PRICE_NODE_VERSION.equals(getVersionResult.getResult());
            if (!correct) {
                handleError(api, nodeType, address, "Incorrect version:" + getVersionResult.getResult());
                continue;
            }

            markAsGoodNode(api, nodeType, address);
        }
    }

    /**
     * NOTE: does not work on MAC netcat version
     */
    public void checkSeedNodes(SlackApi api, List<String> addresses) {
        NodeType nodeType = NodeType.SEED_NODE;
        for (String address : addresses) {
            ProcessResult getFeesResult = executeProcess("./src/main/shell/seednodes.sh " + address, PROCESS_TIMEOUT_SECONDS);
            if (getFeesResult.getError() != null) {
                handleError(api, nodeType, address, getFeesResult.getError());
                continue;
            }
            markAsGoodNode(api, nodeType, address);
        }
    }

    private void checkBitcoinNode(SlackApi api, List<String> ipAddresses, boolean overTor) {
        NodeType nodeType = NodeType.BTC_NODE;
        for (String address : ipAddresses) {
            BitcoinNodeResult result = new BitcoinNodeResult();
            result.setAddress(address);

            try {
                Process pr = rt.exec((overTor ? "torify " : "") + "python ./src/main/python/protocol.py " + address);
                boolean noTimeout = pr.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!noTimeout) {
                    handleError(api, nodeType, address, "Timeout");
                    continue;
                }
                String resultString = convertStreamToString(pr.getInputStream()).replace("\n", " | ");
                String resultStringNoNewlines = resultString.replace("\n", " | ");
                if(!Strings.isNullOrEmpty(resultStringNoNewlines)) {
                    log.info(resultStringNoNewlines);
                }
                String[] splitResult = resultString.split(",");
                if (splitResult.length != 4) {
                    handleError(api, nodeType, address, "Could not parse node output:" + resultStringNoNewlines);
                    continue;
                }
                result.setVersion(splitResult[1]);
                result.setHeight(Long.parseLong(splitResult[2]));
                result.setServices(Integer.parseInt(splitResult[3].trim()));
            } catch (IOException e) {
                handleError(api, nodeType, address, e.getMessage());
                continue;
            } catch (InterruptedException e) {
                handleError(api, nodeType, address, e.getMessage());
                continue;
            }
            markAsGoodNode(api, nodeType, address);
        }
    }

    private ProcessResult executeProcess(String command, int timeoutSeconds) {
        Process pr = null;
        boolean noTimeout = false;
        int exitValue = 0;
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
        exitValue = pr.exitValue();
        return new ProcessResult(convertStreamToString(pr.getInputStream()), (exitValue != 0) ? "Exit value is " + exitValue : null);
    }

    static String convertStreamToString(java.io.InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    public void handleError(SlackApi api, NodeType nodeType, String address, String reason) {
        log.error("Error in {} {}, reason: {}", nodeType.toString(), address, reason);
        NodeDetail node = getNode(address);

        if (NodeType.BTC_NODE.equals(nodeType) && node.hasError() && node.isFirstTimeOffender) {
            SlackTool.send(api, "Error: " + nodeType.getPrettyName() + " " + address + " failed twice", appendBadNodesSizeToString(reason));

        } else if (!NodeType.BTC_NODE.equals(nodeType)) {
            SlackTool.send(api, "Error: " + nodeType.getPrettyName() + " " + address, appendBadNodesSizeToString(reason));
        }
        node.addError(reason);
    }

    private void markAsGoodNode(SlackApi api, NodeType nodeType, String address) {
        Optional<NodeDetail> any = findNodeInfoByAddress(address);
        if(NodeType.BTC_NODE.equals(nodeType) && any.get().hasError() && any.get().isFirstTimeOffender) {
            // no slack logging
            log.info("Fixed: {} {} (first time offender)", nodeType.getPrettyName(), address);
        }else if (any.isPresent() && any.get().hasError()) {
            any.get().clearError();
            log.info("Fixed: {} {}", nodeType.getPrettyName(), address);
            SlackTool.send(api, "Fixed: " + nodeType.getPrettyName() + " " + address, appendBadNodesSizeToString("No longer in error"));
        }
        log.info("{} with address {} is OK", nodeType.getPrettyName(), address);
    }

    private Optional<NodeDetail> findNodeInfoByAddress(String address) {
        return allNodes.stream().filter(nodeDetail -> nodeDetail.getAddress().equals(address)).findAny();
    }

    private NodeDetail getNode(String address) {
        Optional<NodeDetail> any = findNodeInfoByAddress(address);
        return any.get();
    }

    private String appendBadNodesSizeToString(String body) {
        return body + " (now " + getErrorCount() + " node(s) have errors, next check in +/-" + Math.round(LOOP_SLEEP_SECONDS / 60) + " minutes)";
    }

    public String printAllNodesReportSlack() {
        long errorCount = getErrorCount();
        if (errorCount == 0) {
            return "";
        }
        return "Nodes in error: *" + errorCount + "*. Monitoring node started at: " + startTime.toString() + "\n" +
                allNodes.stream().sorted().map(nodeDetail -> padRight(nodeDetail.getNodeType().getPrettyName(), 15)
                        + "\t|\t`" + padRight(nodeDetail.getAddress(), 27)
                        + "` " + (nodeDetail.hasError() ? "*In Error*" : padRight("", 8))
                        + " #errors: " + padRight(String.valueOf(nodeDetail.getNrErrorsSinceStart()), 5)
                        + "\t# error minutes: " + padRight(String.valueOf(nodeDetail.getErrorMinutesSinceStart()), 6)
                        + ((nodeDetail.getErrorReason().size() > 0) ? " reasons: " + nodeDetail.getReasonListAsString() : ""))
                        .collect(Collectors.joining("\n"));
    }

    public String printAllNodesReportHtml() {
        long errorCount = getErrorCount();
        if (errorCount == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("<html><body><h1>");
        builder.append("Nodes in error: <b>" + errorCount + "</b><br/>Monitoring node started at: " + startTime.toString() +
                "<br/><table style=\"width:100%\"><tr><th align=\"left\">Node Type</th><th align=\"left\">Address</th><th align=\"left\">Error?</th><th align=\"left\">Nr. of errors</th><th align=\"left\">Total error minutes</th><th align=\"left\">Reasons</th></tr>" +
                allNodes.stream().sorted().map(nodeDetail -> "<tr>"
                        +" <td>" + nodeDetail.getNodeType().getPrettyName() + "</td>"
                        + "<td>" + nodeDetail.getAddress() + "</td>"
                        + "<td>" + (nodeDetail.hasError() ? "<b>Yes</b>" : "") + "</td>"
                        + "<td>" + String.valueOf(nodeDetail.getNrErrorsSinceStart()) + "</td>"
                        + "<td>" + String.valueOf(nodeDetail.getErrorMinutesSinceStart()) + "</td>"
                        + "<td>" + ((nodeDetail.getErrorReason().size() > 0) ? " reasons: " + nodeDetail.getReasonListAsString() : "") + "</td>"
                        + "</tr>")
                        .collect(Collectors.joining("")));
        builder.append("</table></body></html>");
        return builder.toString();
    }

    private long getErrorCount() {
        return allNodes.stream().filter(nodeDetail -> nodeDetail.hasError()).count();
    }

    private String padRight(String s, int padding) {
        String formatString = "%1$-" + String.valueOf(padding) + "s";
        return String.format(formatString, s);
    }

    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();
        parser.accepts(USE_SLACK, "Output is posted to a slack channel")
                .withRequiredArg().ofType(Boolean.class);
        parser.accepts(SLACK_PRICE_SECRET, "The pricenode slack secret URL")
                .withOptionalArg().ofType(String.class);
        parser.accepts(SLACK_SEED_SECRET, "The seednode slack secret URL")
                .withOptionalArg().ofType(String.class);
        parser.accepts(SLACK_BTC_SECRET, "The btc fullnode slack secret URL")
                .withOptionalArg().ofType(String.class);
        parser.accepts(LOCAL_YAML, "Override the default nodes.yaml file with a local file")
                .withRequiredArg().ofType(String.class);

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

        //
        isSlackEnabled = options.has(USE_SLACK) ? (boolean) options.valueOf(USE_SLACK) : false;
        slackPriceSecretData = (options.has(SLACK_PRICE_SECRET) && options.hasArgument(SLACK_PRICE_SECRET)) ? (String) options.valueOf(SLACK_PRICE_SECRET) : null;
        slackSeedSecretData = (options.has(SLACK_SEED_SECRET) && options.hasArgument(SLACK_SEED_SECRET)) ? (String) options.valueOf(SLACK_SEED_SECRET) : null;
        slackBTCSecretData = (options.has(SLACK_BTC_SECRET) && options.hasArgument(SLACK_BTC_SECRET)) ? (String) options.valueOf(SLACK_BTC_SECRET) : null;

        if (isSlackEnabled) {
            log.info("Slack enabled");
            if(slackPriceSecretData != null) {
                log.info("Using Price slack secret: {}", slackPriceSecretData);
                priceApi = new SlackApi(slackPriceSecretData);
            }
            if(slackSeedSecretData != null) {
                log.info("Using Seed slack secret: {}", slackSeedSecretData);
                seedApi = new SlackApi(slackSeedSecretData);
            }
            if(slackBTCSecretData != null) {
                log.info("Using BTC full node slack secret: {}", slackBTCSecretData);
                btcApi = new SlackApi(slackBTCSecretData);
            }

            if(priceApi == null && seedApi == null && btcApi == null) {
                log.info("Slack disabled due to missing slack secret");
                isSlackEnabled = false;
            }
        }

        localYamlData = (options.has(LOCAL_YAML) && options.hasArgument(LOCAL_YAML)) ? (String) options.valueOf(LOCAL_YAML) : null;
        if(localYamlData != null) {
            log.info("Using local yaml file: {}", localYamlData);
        }

        Monitoring monitoring = new Monitoring(localYamlData);

        log.info("Startup. All nodes in error will be shown fully in this first run.");
        int counter = 0;
        boolean isReportingLoop;

        // add all nodes to the node info list
        monitoring.allNodes.addAll(onionPriceNodes.stream().map(s -> new NodeDetail(s, NodeType.PRICE_NODE)).collect(Collectors.toList()));
        monitoring.allNodes.addAll(clearnetBitcoinNodes.stream().map(s -> new NodeDetail(s, NodeType.BTC_NODE)).collect(Collectors.toList()));
        monitoring.allNodes.addAll(onionBitcoinNodes.stream().map(s -> new NodeDetail(s, NodeType.BTC_NODE)).collect(Collectors.toList()));
        monitoring.allNodes.addAll(seedNodes.stream().map(s -> new NodeDetail(s, NodeType.SEED_NODE)).collect(Collectors.toList()));

        try {
            Thread.sleep(10000); //wait 10 seconds so that tor is started
        } catch (InterruptedException e) {
            log.error("Failed during initial sleep", e);
        }
        port(8080);
        get("/ping", (req, res) -> "pong");
        get("/status", (req, res) -> monitoring.printAllNodesReportHtml());
        Logger.getLogger("org").setLevel(Level.OFF);
        Logger.getLogger("akka").setLevel(Level.OFF);

        while (true) {
            try {
                log.info("Starting checks...");
                monitoring.checkPriceNodes(priceApi, onionPriceNodes, true);
                monitoring.checkClearnetBitcoinNodes(btcApi, clearnetBitcoinNodes);
                monitoring.checkOnionBitcoinNodes(btcApi, onionBitcoinNodes);
                monitoring.checkSeedNodes(seedApi, seedNodes);
                log.info("Stopping checks, now sleeping for {} seconds.", LOOP_SLEEP_SECONDS);

                /*
                // prepare reporting
                isReportingLoop = (counter % REPORTING_NR_LOOPS == 0);
                if (isReportingLoop) {
                    String errorNodeOutputString = monitoring.printAllNodesReport();
                    if (!errorNodeOutputString.isEmpty()) {
                        log.info("Nodes in error: \n{}", errorNodeOutputString);
                        SlackTool.send(api, NodeType.MONITORING_NODE.getPrettyName(), errorNodeOutputString);
                    } else {
                        log.info("No errors");
                        SlackTool.send(api, NodeType.MONITORING_NODE.getPrettyName(), "No errors");
                    }
                }
                */
            } catch (Throwable e) {
                log.error("Could not send message to slack", e);
            }
            counter++;
            try {
                Thread.sleep(1000 * LOOP_SLEEP_SECONDS);
            } catch (InterruptedException e) {
                log.error("Error during sleep", e);
            }
        }
    }
}
