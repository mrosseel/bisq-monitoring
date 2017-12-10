package io.bisq.monitoring;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.gpedro.integrations.slack.SlackApi;
import org.berndpruenster.netlayer.tor.NativeTor;
import org.berndpruenster.netlayer.tor.Tor;
import org.berndpruenster.netlayer.tor.TorCtlException;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.VersionMessage;
import org.bitcoinj.net.BlockingClient;
import org.bitcoinj.params.MainNetParams;
import org.jetbrains.annotations.Nullable;

import javax.net.SocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
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
    @Getter
    private NodeConfig nodeConfig;

    // CMD line arguments
    public static final String USE_SLACK = "useSlack";
    public static final String SLACK_PRICE_SECRET = "slackPriceSecret";
    public static final String SLACK_SEED_SECRET = "slackSeedSecret";
    public static final String SLACK_BTC_SECRET = "slackBTCSecret";
    public static final String LOCAL_YAML = "localYaml";
    public static final String TEST = "test";

    // CMD line argument DATA
    public static boolean isSlackEnabled = false;
    public static String slackPriceSecretData = null;
    public static String slackSeedSecretData = null;
    public static String slackBTCSecretData = null;
    public static String localYamlData = null;
    public static boolean isTest = false;

    public static int LOOP_SLEEP_SECONDS = 10 * 60;
    public static int UNREPORTED_ERRORS_THRESHOLD = 3;
    public static int processTimeoutSeconds;

    Set<NodeDetail> allNodes = new HashSet<>();
    LocalDateTime startTime = LocalDateTime.now();
    // one tor is started, this is filled in
    ProxySocketFactory proxySocketFactory;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(50);

    public Monitoring(NodeConfig nodeConfig) {
        this.nodeConfig = nodeConfig;
        this.processTimeoutSeconds = nodeConfig.getNodeTimeoutSecs();
    }

    public void checkBitcoinNode(List<NodeDetail> nodes, SlackApi api) {
        int CONNECT_TIMEOUT_MSEC = processTimeoutSeconds * 1000;
        MainNetParams params = MainNetParams.get();
        Context context = new Context(params);

        for (NodeDetail node : nodes) {
            Runnable retry = () -> this.checkBitcoinNode(Lists.newArrayList(node), api);
            try {
                PeerAddress remoteAddress = new PeerAddress(node.getAddress(), node.getPort());
                Peer peer = new Peer(params, new VersionMessage(params, 100000), remoteAddress, null);
                BlockingClient blockingClient =
                        new BlockingClient(node.isTor ? remoteAddress.toSocketAddress() : new InetSocketAddress(node.getAddress(), node.getPort()),
                                peer, CONNECT_TIMEOUT_MSEC, node.isTor ? proxySocketFactory : SocketFactory.getDefault(),
                                null);
                ListenableFuture<SocketAddress> connectFuture = blockingClient.getConnectFuture();
                SocketAddress socketAddress = connectFuture.get(CONNECT_TIMEOUT_MSEC, TimeUnit.MILLISECONDS);
                Peer remotePeer = peer.getVersionHandshakeFuture().get(CONNECT_TIMEOUT_MSEC, TimeUnit.MILLISECONDS);
                VersionMessage versionMessage = remotePeer.getPeerVersionMessage();
                if (!verifyBtcNodeVersion(versionMessage)) {
                    handleError(api, node, "BTC Node has wrong version message: " + versionMessage.toString(), retry);
                }
                markAsGoodNode(api, node);
            } catch (InterruptedException e) {
                log.debug("getVersionHandshakeFuture failed {}", e);
                handleError(api, node, "getVersionHandshakeFuture() was interrupted: " + e.getMessage(), retry);
                continue;
            } catch (ExecutionException e) {
                log.debug("getVersionHandshakeFuture failed {}", e);
                handleError(api, node, "getVersionHandshakeFuture() has executionException: " + e.getMessage(), retry);
                continue;
            } catch (IOException e) {
                log.debug("BlockingClient failed {}", e);
                handleError(api, node, "BlockingClient failed: " + e.getMessage(), retry);
                continue;
            } catch (TimeoutException e) {
                log.debug("getVersionHandshakeFuture failed {}", e);
                handleError(api, node,
                        "getVersionHandshakeFuture() has timed out after "
                                + CONNECT_TIMEOUT_MSEC / 1000.0 + " seconds with error: " + e.getMessage(), retry);
                continue;
            }
        }
    }

    private boolean verifyBtcNodeVersion(VersionMessage versionMessage) {
        return versionMessage.localServices == 13 && versionMessage.subVer.contains("0.15");
    }

    @Nullable
    private boolean startTor() {
        InetSocketAddress torProxyAddress = null;
        try {
            Tor.setDefault(new NativeTor(new File("/Users/mike/tor"), null));
            torProxyAddress = new InetSocketAddress("localhost", Tor.getDefault().getProxy().getPort());
        } catch (TorCtlException e) {
            log.error("Error creating Tor Node", e);
            return false;
        }
        Proxy proxy = new Proxy(Proxy.Type.SOCKS, torProxyAddress);
        this.proxySocketFactory = new ProxySocketFactory(proxy);
        return true;
    }

    private void stopTor() {
        Tor.getDefault().shutdown();
        this.proxySocketFactory = null;
    }

    public void scheduleFollowupCheck(NodeDetail node, Runnable retry) {
        Runnable loggingRetry = () -> {
            log.info("Retrying: {} with address {} and {} unreported fails.", node.getNodeType(), node.getAddress(), node.getNrErrorsUnreported());
            retry.run();
        };
        scheduler.schedule(loggingRetry, 60, SECONDS);
    }

    public void checkPriceNodes(List<NodeDetail> nodes, SlackApi api) {
        for (NodeDetail node : nodes) {
            Runnable retry = () -> this.checkPriceNodes(Lists.newArrayList(node), api);

            ProcessResult getFeesResult = executeProcess((node.isTor ? "torify " : "") + "curl " + node.getAddress() + (node.isTor ? "" : node.getPort()) + "/getFees", processTimeoutSeconds);
            if (getFeesResult.getError() != null) {
                handleError(api, node, getFeesResult.getError(), retry);
                continue;
            }
            boolean correct = getFeesResult.getResult().contains("btcTxFee");
            if (!correct) {
                handleError(api, node, "Result does not contain expected keyword: " + getFeesResult.getResult(), retry);
                continue;
            }

            ProcessResult getVersionResult = executeProcess((node.isTor ? "torify " : "") + "curl " + node.getAddress() + (node.isTor ? "" : "8080") + "/getVersion", processTimeoutSeconds);
            if (getVersionResult.getError() != null) {
                handleError(api, node, getVersionResult.getError(), retry);
                continue;
            }
            correct = nodeConfig.getPricenodeVersion().equals(getVersionResult.getResult());
            if (!correct) {
                handleError(api, node, "Incorrect version:" + getVersionResult.getResult(), retry);
                continue;
            }

            ProcessResult getPricesResult = executeProcess((node.isTor ? "torify " : "") + "curl " + node.getAddress() + (node.isTor ? "" : "8080") + "/getAllMarketPrices", processTimeoutSeconds);
            if (getPricesResult.getError() != null) {
                handleError(api, node, getPricesResult.getError(), retry);
                continue;
            }
            correct = getPricesResult.getResult().contains("\"currencyCode\": \"BTC\"");
            if (!correct) {
                handleError(api, node, "getAllMarketPrices does not contain our test string", retry);
                continue;
            }

            markAsGoodNode(api, node);
        }
    }

    /**
     * NOTE: does not work on MAC netcat version
     */
    public void checkSeedNodes(List<NodeDetail> nodes, SlackApi api) {
        for (NodeDetail node : nodes) {
            Runnable retry = () -> this.checkSeedNodes(Lists.newArrayList(node), api);
            ProcessResult getFeesResult = executeProcess("./src/main/shell/seednodes.sh " + node.getAddress() + ":" + node.getPort(), processTimeoutSeconds);
            if (getFeesResult.getError() != null) {
                handleError(api, node, getFeesResult.getError(), retry);
                continue;
            }
            markAsGoodNode(api, node);
        }
    }

    private ProcessResult executeProcess(String command, int timeoutSeconds) {
        Process pr = null;
        boolean noTimeout = false;
        int exitValue = 0;
        try {
            pr = rt.exec(command);
            noTimeout = pr.waitFor(timeoutSeconds, SECONDS);
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

    public void handleError(SlackApi api, NodeDetail node, String reason, Runnable retry) {
        NodeType nodeType = node.getNodeType();
        String address = node.getAddress();
        String owner = node.getOwner();
        node.addError(reason);

        if (node.nrErrorsUnreported == UNREPORTED_ERRORS_THRESHOLD) {
            log.error("Error in {} {} ({}), reason: {}", nodeType.toString(), address, owner, reason);
            SlackTool.send(api, "Error: " + nodeType.getPrettyName() + " " + address + " failed " + UNREPORTED_ERRORS_THRESHOLD + " times", "<"+owner+"> " + appendBadNodesSizeToString(reason));
        } else if(node.nrErrorsUnreported < UNREPORTED_ERRORS_THRESHOLD){
            log.debug("Scheduling a followup check for node: {}", node.toString());
            scheduleFollowupCheck(node, retry);
        }
    }

    private void markAsGoodNode(SlackApi api, NodeDetail node) {
        String address = node.getAddress();
        NodeType nodeType = node.getNodeType();
        Optional<NodeDetail> any = findNodeInfoByAddress(address);
        if (node.nrErrorsUnreported > 0 && node.nrErrorsUnreported < UNREPORTED_ERRORS_THRESHOLD) {
            // no slack logging
            log.info("Fixed: {} {} (" + node.nrErrorsUnreported + " unreported errors)", nodeType.getPrettyName(), address);
        } else if (node.nrErrorsUnreported >= UNREPORTED_ERRORS_THRESHOLD) {
            log.info("Fixed: {} {}", nodeType.getPrettyName(), address);
            SlackTool.send(api, "Fixed: " + nodeType.getPrettyName() + " " + address + " (" + node.getOwner() + ")", appendBadNodesSizeToString("No longer in error"));
        }
        any.get().clearError();
        log.info("OK: {} with address {}", nodeType.getPrettyName(), address);
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

        StringBuilder builder = new StringBuilder();
        builder.append("<html><body><h1>");
        builder.append("Nodes in error: <b>" + errorCount + "</b><br/>Monitoring node started at: " + startTime.toString() +
                "<br/><table style=\"width:100%\"><tr><th align=\"left\">Node Type</th><th align=\"left\">Address</th><th align=\"left\">Owner</th><th align=\"left\">Error?</th><th align=\"left\">Total errors</th><th align=\"left\">Error streak</th><th align=\"left\">Total error minutes</th><th align=\"left\">Reasons</th></tr>" +
                allNodes.stream()
                        .sorted(Comparator.comparing(node -> (node.getNodeType() + node.getOwner())))
                        .map(nodeDetail -> "<tr>"
                        + " <td>" + nodeDetail.getNodeType().getPrettyName() + "</td>"
                        + "<td>" + nodeDetail.getAddress() + "</td>"
                        + "<td>" + nodeDetail.getOwner() + "</td>"
                        + "<td>" + (nodeDetail.hasError() ? "<b>Yes</b>" : "") + "</td>"
                        + "<td>" + String.valueOf(nodeDetail.getNrErrorsSinceStart()) + "</td>"
                        + "<td>" + String.valueOf(nodeDetail.getNrErrorsUnreported()) + "</td>"
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


    public static String inputStreamToString(InputStream is) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            return br.lines().collect(Collectors.joining(System.lineSeparator()));
        }
    }

    private static List<NodeDetail> getSeednodesFromConfig(Monitoring monitoring) {
        return monitoring.getNodeConfig().getSeednodes().stream().map(s -> new NodeDetail(s.getAddress(), s.getPort(), s.getOwner(), NodeType.SEED_NODE, s.isTor())).collect(Collectors.toList());
    }

    private static List<NodeDetail> getBtcNodesFromConfig(Monitoring monitoring) {
        return monitoring.getNodeConfig().getBtcnodes().stream().map(s -> new NodeDetail(s.getAddress(), 8333, s.getOwner(), NodeType.BTC_NODE, s.isTor())).collect(Collectors.toList());
    }

    private static List<NodeDetail> getPricenodesFromConfig(Monitoring monitoring) {
        return monitoring.getNodeConfig().getPricenodes().stream().map(s -> new NodeDetail(s.getAddress(), 8080, s.getOwner(), NodeType.PRICE_NODE, s.isTor())).collect(Collectors.toList());
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
        parser.accepts(TEST, "do a test run of the app, without actually checking anything");

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
        isTest = (options.has(TEST));

        if (isSlackEnabled) {
            log.info("Slack enabled");
            if (slackPriceSecretData != null) {
                log.info("Using Price slack secret: {}", slackPriceSecretData);
                priceApi = new SlackApi(slackPriceSecretData);
            }
            if (slackSeedSecretData != null) {
                log.info("Using Seed slack secret: {}", slackSeedSecretData);
                seedApi = new SlackApi(slackSeedSecretData);
            }
            if (slackBTCSecretData != null) {
                log.info("Using BTC full node slack secret: {}", slackBTCSecretData);
                btcApi = new SlackApi(slackBTCSecretData);
            }

            if (priceApi == null && seedApi == null && btcApi == null) {
                log.info("Slack disabled due to missing slack secret");
                isSlackEnabled = false;
            }
        }

        localYamlData = (options.has(LOCAL_YAML) && options.hasArgument(LOCAL_YAML)) ? (String) options.valueOf(LOCAL_YAML) : null;
        String yamlContent = "";
        if (localYamlData != null) {
            log.info("Using local yaml file: {}", localYamlData);
            yamlContent = new String(Files.readAllBytes(Paths.get(localYamlData)));
        } else {
            log.info("Using yaml file from classpath");
            yamlContent = inputStreamToString(Monitoring.class.getResourceAsStream("/bisq_nodes.yaml"));
        }
        NodeYamlReader reader = new NodeYamlReader(yamlContent);

        Monitoring monitoring = new Monitoring(reader.getNodeConfig());

        log.info("Startup. All nodes in error will be shown fully in this first run.");
        int counter = 0;
        boolean isReportingLoop;

        // add all nodes to the node info list
        List<NodeDetail> pricenodesFromConfig = getPricenodesFromConfig(monitoring);
        List<NodeDetail> btcNodesFromConfig = getBtcNodesFromConfig(monitoring);
        List<NodeDetail> seednodesFromConfig = getSeednodesFromConfig(monitoring);
        monitoring.allNodes.addAll(pricenodesFromConfig);
        monitoring.allNodes.addAll(btcNodesFromConfig);
        monitoring.allNodes.addAll(seednodesFromConfig);

        if (!isTest) {
            try {
                Thread.sleep(10000); //wait 10 seconds so that tor is started
            } catch (InterruptedException e) {
                log.error("Failed during initial sleep", e);
            }
        }
        port(8080);
        get("/ping", (req, res) -> "pong");
        get("/status", (req, res) -> monitoring.printAllNodesReportHtml());
        Logger.getLogger("org").setLevel(Level.OFF);
        Logger.getLogger("akka").setLevel(Level.OFF);

        if (isTest) {
            log.info("Tests skipped due to --test flag");
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        monitoring.startTor();

        while (true) {
            try {
                log.info("Starting checks...");
                monitoring.checkPriceNodes(pricenodesFromConfig, priceApi);
                monitoring.checkSeedNodes(seednodesFromConfig, seedApi);
                monitoring.checkBitcoinNode(btcNodesFromConfig, btcApi);
                log.info("Stopping checks, now sleeping for {} seconds.", LOOP_SLEEP_SECONDS);
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
