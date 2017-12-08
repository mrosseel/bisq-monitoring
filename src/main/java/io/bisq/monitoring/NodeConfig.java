package io.bisq.monitoring;

import lombok.Data;

import java.util.ArrayList;

/*

 */
@Data
public class NodeConfig {
    private int nodeTimeoutSecs;
    private String pricenodeVersion;
    private ArrayList<Node> pricenodes;
    private ArrayList<Node> seednodes;
    private ArrayList<Node> btcnodes;
}

@Data
class Node {
    public String address;
    public int port;
    public String owner;

    public boolean isTor() {
        return address.contains(".onion");
    }
}
