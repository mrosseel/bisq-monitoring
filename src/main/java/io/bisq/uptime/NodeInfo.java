package io.bisq.uptime;

import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/*

 */
@Data
@Slf4j
public class NodeInfo implements Comparable<NodeInfo> {
    String address;
    NodeType nodeType;
    long nrErrorsSinceStart = 0;
    LocalDateTime startTime = LocalDateTime.now();
    Optional<LocalDateTime> lastErrorTime = Optional.empty();
    long errorMinutesSinceStart = 0;

    List<String> errorReason = new ArrayList<>();

    public NodeInfo(String address, NodeType nodeType) {
        this.address = address;
        this.nodeType = nodeType;
    }

    @Override
    public int compareTo(NodeInfo o) {
        return getNodeType().compareTo(o.getNodeType());
    }


    public boolean hasError() {
        return getLastErrorTime().isPresent();
    }

    /**
     *
     * @return true if an existing error condition was updated, false if a new error was added
     */
    public boolean addError(String reason) {
        LocalDateTime now = LocalDateTime.now();
        String finalReason = (reason == null || reason.isEmpty()) ? "Empty reason" : reason;
        if(!getErrorReason().contains(finalReason)) {
            getErrorReason().add(finalReason);
        }
        setNrErrorsSinceStart(getNrErrorsSinceStart()+1);

        if (!hasError()) {
            setLastErrorTime(Optional.of(now));
            return false;
        } else {
            setErrorMinutesSinceStart(getErrorMinutesSinceStart()
                    + ChronoUnit.MINUTES.between(now, getLastErrorTime().get()));
            setLastErrorTime(Optional.of(now));
            return true;
        }
    }

    public boolean clearError() {
        if(!getLastErrorTime().isPresent()) {
            log.debug("No error time was present when calling clearError");
            return false;
        }
        setErrorMinutesSinceStart(getErrorMinutesSinceStart()
                + ChronoUnit.MINUTES.between(getLastErrorTime().get(), LocalDateTime.now()));
        setLastErrorTime(Optional.empty());
        getErrorReason().clear();
        return true;
    }

    public String getReasonListAsString() {
        return errorReason.stream().collect(Collectors.joining(" | "));
    }
}

enum NodeType {
    PRICE_NODE("Price node"), SEED_NODE("Seed node"), BTC_NODE("Bitcoin node"), MONITORING_NODE("Monitoring node");

    @Getter
    private final String prettyName;

    NodeType(String prettyName) {
        this.prettyName = prettyName;
    }

}
