package io.bisq.monitoring;

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
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    public static int PROCESS_TIMEOUT_SECONDS = 60;

    Set<NodeDetail> allNodes = new HashSet<>();
    LocalDateTime startTime = LocalDateTime.now();
    // one tor is started, this is filled in
    ProxySocketFactory proxySocketFactory;

    public Monitoring(NodeConfig nodeConfig) {
        this.nodeConfig = nodeConfig;
    }

    public void checkBitcoinNode(SlackApi api) {
        List<NodeDetail> nodes = allNodes.stream().filter(node -> NodeType.BTC_NODE.equals(node.getNodeType())).collect(Collectors.toList());

        int CONNECT_TIMEOUT_MSEC = 60 * 1000;  // same value used in bitcoinj.
        MainNetParams params = MainNetParams.get();
        Context context = new Context(params);

        for (NodeDetail node : nodes) {
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
                    handleError(api, NodeType.BTC_NODE, node.getAddress(), node.getOwner(), "BTC Node has wrong version message: " + versionMessage.toString());
                }
                markAsGoodNode(api, NodeType.BTC_NODE, node.getAddress());
            } catch (InterruptedException e) {
                log.debug("getVersionHandshakeFuture failed {}", e);
                handleError(api, NodeType.BTC_NODE, node.getAddress(), node.getOwner(), "getVersionHandshakeFuture() was interrupted: " + e.getMessage());
                continue;
            } catch (ExecutionException e) {
                log.debug("getVersionHandshakeFuture failed {}", e);
                handleError(api, NodeType.BTC_NODE, node.getAddress(), node.getOwner(), "getVersionHandshakeFuture() has executionException: " + e.getMessage());
                continue;
            } catch (IOException e) {
                log.debug("BlockingClient failed {}", e);
                handleError(api, NodeType.BTC_NODE, node.getAddress(), node.getOwner(), "BlockingClient failed: " + e.getMessage());
                continue;
            } catch (TimeoutException e) {
                log.debug("getVersionHandshakeFuture failed {}", e);
                handleError(api, NodeType.BTC_NODE, node.getAddress(), node.getOwner(),
                        "getVersionHandshakeFuture() has timed out after "
                                + CONNECT_TIMEOUT_MSEC / 1000.0 + " seconds with error: " + e.getMessage());
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

    public void checkPriceNodes(SlackApi api) {
        NodeType nodeType = NodeType.PRICE_NODE;
        List<NodeDetail> nodes = allNodes.stream().filter(node -> NodeType.PRICE_NODE.equals(node.getNodeType())).collect(Collectors.toList());
        for (NodeDetail node : nodes) {
            ProcessResult getFeesResult = executeProcess((node.isTor ? "torify " : "") + "curl " + node.getAddress() + (node.isTor ? "" : node.getPort()) + "/getFees", PROCESS_TIMEOUT_SECONDS);
            if (getFeesResult.getError() != null) {
                handleError(api, nodeType, node.getAddress(), node.getOwner(), getFeesResult.getError());
                continue;
            }
            boolean correct = getFeesResult.getResult().contains("btcTxFee");
            if (!correct) {
                handleError(api, nodeType, node.getAddress(), node.getAddress(), "Result does not contain expected keyword: " + getFeesResult.getResult());
                continue;
            }

            ProcessResult getVersionResult = executeProcess((node.isTor ? "torify " : "") + "curl " + node.getAddress() + (node.isTor ? "" : "8080") + "/getVersion", PROCESS_TIMEOUT_SECONDS);
            if (getVersionResult.getError() != null) {
                handleError(api, nodeType, node.getAddress(), node.getAddress(), getVersionResult.getError());
                continue;
            }
            correct = nodeConfig.getPricenodeVersion().equals(getVersionResult.getResult());
            if (!correct) {
                handleError(api, nodeType, node.getAddress(), node.getAddress(), "Incorrect version:" + getVersionResult.getResult());
                continue;
            }

            markAsGoodNode(api, nodeType, node.getAddress());
        }
    }

    /**
     * NOTE: does not work on MAC netcat version
     */
    public void checkSeedNodes(SlackApi api) {
        NodeType nodeType = NodeType.SEED_NODE;
        List<NodeDetail> nodes = allNodes.stream().filter(node -> NodeType.SEED_NODE.equals(node.getNodeType())).collect(Collectors.toList());
        for (NodeDetail node : nodes) {
            ProcessResult getFeesResult = executeProcess("./src/main/shell/seednodes.sh " + node.getAddress(), PROCESS_TIMEOUT_SECONDS);
            if (getFeesResult.getError() != null) {
                handleError(api, nodeType, node.getAddress(), node.getOwner(), getFeesResult.getError());
                continue;
            }
            markAsGoodNode(api, nodeType, node.getAddress());
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

    public void handleError(SlackApi api, NodeType nodeType, String address, String owner, String reason) {
        NodeDetail node = getNode(address);

        if (NodeType.BTC_NODE.equals(nodeType) && node.hasError() && node.isFirstTimeOffender) { // btc node with error and that error was its first error, we log
            log.error("Error in {} {} ({}) - failed twice, reason: {}", nodeType.toString(), address, owner, reason);
            SlackTool.send(api, "Error: " + nodeType.getPrettyName() + " " + address + "(" + owner + ") failed twice", appendBadNodesSizeToString(reason));

        } else if (!NodeType.BTC_NODE.equals(nodeType) && !node.hasError()) { // first time node has error, we log
            log.error("Error in {} {} ({}), reason: {}", nodeType.toString(), address, owner, reason);
            SlackTool.send(api, "Error: " + nodeType.getPrettyName() + " " + address + "(" + owner + ")", appendBadNodesSizeToString(reason));
        }
        node.addError(reason);
    }

    private void markAsGoodNode(SlackApi api, NodeType nodeType, String address) {
        Optional<NodeDetail> any = findNodeInfoByAddress(address);
        if (NodeType.BTC_NODE.equals(nodeType) && any.get().hasError() && any.get().isFirstTimeOffender) {
            // no slack logging
            log.info("Fixed: {} {} (first time offender)", nodeType.getPrettyName(), address);
        } else if (any.isPresent() && any.get().hasError()) {
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

        StringBuilder builder = new StringBuilder();
        builder.append("<html><body><h1>");
        builder.append("Nodes in error: <b>" + errorCount + "</b><br/>Monitoring node started at: " + startTime.toString() +
                "<br/><table style=\"width:100%\"><tr><th align=\"left\">Node Type</th><th align=\"left\">Address</th><th align=\"left\">Error?</th><th align=\"left\">Nr. of errors</th><th align=\"left\">Total error minutes</th><th align=\"left\">Reasons</th></tr>" +
                allNodes.stream().sorted().map(nodeDetail -> "<tr>"
                        + " <td>" + nodeDetail.getNodeType().getPrettyName() + "</td>"
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
            try {
                yamlContent = new String(Files.readAllBytes(Paths.get(Monitoring.class.getResource("/bisq_nodes.yaml").toURI())));
            } catch (URISyntaxException e) {
                log.error("Erorr reading nodes yaml config from classpath", e);
                return;
            }
        }
        NodeYamlReader reader = new NodeYamlReader(yamlContent);

        Monitoring monitoring = new Monitoring(reader.getNodeConfig());

        log.info("Startup. All nodes in error will be shown fully in this first run.");
        int counter = 0;
        boolean isReportingLoop;

        // add all nodes to the node info list
        monitoring.allNodes.addAll(getPricenodesFromConfig(monitoring));
        monitoring.allNodes.addAll(getBtcNodesFromConfig(monitoring));
        monitoring.allNodes.addAll(getSeednodesFromConfig(monitoring));

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
                monitoring.checkPriceNodes(priceApi);
                monitoring.checkSeedNodes(seedApi);
                monitoring.checkBitcoinNode(btcApi);
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

    private static List<NodeDetail> getSeednodesFromConfig(Monitoring monitoring) {
        return monitoring.getNodeConfig().getSeednodes().stream().map(s -> new NodeDetail(s.getAddress(), s.getPort(), s.getOwner(), NodeType.SEED_NODE, s.isTor())).collect(Collectors.toList());
    }

    private static List<NodeDetail> getBtcNodesFromConfig(Monitoring monitoring) {
        return monitoring.getNodeConfig().getBtcnodes().stream().map(s -> new NodeDetail(s.getAddress(), 8333, s.getOwner(), NodeType.BTC_NODE, s.isTor())).collect(Collectors.toList());
    }

    private static List<NodeDetail> getPricenodesFromConfig(Monitoring monitoring) {
        return monitoring.getNodeConfig().getPricenodes().stream().map(s -> new NodeDetail(s.getAddress(), 8080, s.getOwner(), NodeType.PRICE_NODE, s.isTor())).collect(Collectors.toList());
    }

}
