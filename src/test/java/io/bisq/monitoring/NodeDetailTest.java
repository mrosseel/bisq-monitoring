package io.bisq.monitoring;

/*

 */

import org.junit.Test;

import static org.junit.Assert.*;

public class NodeDetailTest {
    @Test
    public void addError() throws Exception {
        NodeDetail nodeDetail = new NodeDetail("123address", NodeType.SEED_NODE);

        assertFalse(nodeDetail.hasError());
        assertEquals(0, nodeDetail.getErrorMinutesSinceStart());
        assertFalse(nodeDetail.getLastErrorTime().isPresent());
        assertEquals("123address", nodeDetail.getAddress());
        assertNotNull(nodeDetail.getStartTime());
        assertEquals(0, nodeDetail.getNrErrorsSinceStart());


        nodeDetail.addError("BlaError");
        assertTrue(nodeDetail.hasError());
        assertTrue(nodeDetail.getLastErrorTime().isPresent());
        assertTrue(nodeDetail.getReasonListAsString().length() > 0);
        assertEquals(1, nodeDetail.getNrErrorsSinceStart());

        nodeDetail.clearError();
        assertFalse(nodeDetail.hasError());
        assertFalse(nodeDetail.getLastErrorTime().isPresent());
        assertTrue(nodeDetail.getReasonListAsString().length() == 0);
        assertEquals(1, nodeDetail.getNrErrorsSinceStart());

        // clearing a second time gives no errors
        nodeDetail.clearError();

        // Error counter increases
        nodeDetail.addError("BlaError");
        assertEquals(2, nodeDetail.getNrErrorsSinceStart());

        // Error counter increases, also if already in error
        nodeDetail.addError("BlaError");
        assertEquals(3, nodeDetail.getNrErrorsSinceStart());
    }
}
